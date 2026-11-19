package dev.mixeddeno.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import dev.mixeddeno.core.Configurer
import dev.mixeddeno.core.DenoProcess
import dev.mixeddeno.core.Generator
import dev.mixeddeno.core.Problem
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MixedDenoService(private val project: Project) : Disposable {
  private val log = logger<MixedDenoService>()
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val running = AtomicBoolean(false)
  @Volatile private var pending = false
  @Volatile private var lastFingerprint: String? = null

  @Volatile var problemsByFile: Map<String, List<Problem>> = emptyMap(); private set
  @Volatile var externalRoots: Set<Path> = emptySet(); private set

  companion object {
    fun get(project: Project): MixedDenoService = project.getService(MixedDenoService::class.java)
    private val CONFIG_NAMES = setOf("deno.json", "deno.jsonc", "deno.lock", "package.json", "tsconfig.json")
    private val SOURCE = Regex(".*\\.(m?ts|tsx)$")
  }

  val root: Path? get() = project.guessProjectDir()?.toNioPath()

  init {
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        if (!MixedDenoSettings.get(project).state.autoGenerate) return
        val base = root?.toString() ?: return
        val relevant = events.any { e ->
          val path = e.path
          if (!path.startsWith(base) || path.contains("/node_modules/") || path.contains("/${Generator.OUT_DIR}/")) return@any false
          val name = path.substringAfterLast('/')
          name in CONFIG_NAMES || SOURCE.matches(name)
        }
        if (relevant) schedule(force = false)
      }
    })
  }

  fun schedule(force: Boolean, delayMs: Long = 1500) {
    alarm.cancelAllRequests()
    alarm.addRequest({ if (force) lastFingerprint = null; generate(interactive = false) }, delayMs)
  }

  fun generate(interactive: Boolean) {
    val root = root ?: return
    if (!running.compareAndSet(false, true)) { pending = true; return }
    val task = object : Task.Backgroundable(project, "Generating Deno types", true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          indicator.text = "Scanning deno.json / package.json scopes"
          val fp = Generator.fingerprint(root)
          if (!interactive && fp == lastFingerprint) return
          val deno = DenoProcess.locate(MixedDenoSettings.get(project).state.denoPath)
          if (deno == null) {
            notify("Deno executable not found. Set it in Settings | Languages & Frameworks | Mixed Deno Types.", NotificationType.WARNING)
            return
          }
          indicator.text = "Resolving jsr:/npm: modules with deno info"
          val result = Generator(root, DenoProcess(deno)).run()
          lastFingerprint = fp
          problemsByFile = result.problems.groupBy { it.file }
          val rootsChanged = externalRoots != result.externalRoots
          externalRoots = result.externalRoots
          LocalFileSystem.getInstance().refreshNioFiles(result.written + root.resolve(Generator.OUT_DIR), true, true) {
            ApplicationManager.getApplication().invokeLater({
              if (project.isDisposed) return@invokeLater
              if (rootsChanged) ApplicationManager.getApplication().runWriteAction {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
              }
              DaemonCodeAnalyzer.getInstance(project).restart()
            }, project.disposed)
          }
          val errors = result.log.filter { it.startsWith("ERROR") }
          if (interactive || errors.isNotEmpty()) {
            notify(
              (if (errors.isEmpty()) "Deno types generated" else "Deno types generated with errors") +
                ":<br>" + result.log.joinToString("<br>") +
                (if (result.problems.isNotEmpty()) "<br>${result.problems.size} import problem(s), see .deno-types/report.json" else ""),
              if (errors.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
            )
          }
        } catch (e: Exception) {
          log.warn(e)
          notify("Deno type generation failed: ${e.message}", NotificationType.ERROR)
        } finally {
          running.set(false)
          if (pending) { pending = false; schedule(force = false, delayMs = 500) }
        }
      }
    }
    task.queue()
  }

  fun configureTsconfigs() {
    val root = root ?: return
    val changes = Configurer(root).run()
    LocalFileSystem.getInstance().refreshNioFiles(listOf(root), true, true, null)
    notify(if (changes.isEmpty()) "tsconfig files already configured" else "Configured:<br>" + changes.joinToString("<br>"), NotificationType.INFORMATION)
    generate(interactive = true)
  }

  fun notify(content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Mixed Deno Types")
      .createNotification("Mixed Deno Types", content, type).notify(project)
  }

  override fun dispose() {}
}
