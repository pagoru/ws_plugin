package dev.mixeddeno.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.mixeddeno.core.Workspace
import dev.mixeddeno.core.ScopeKind

class MixedDenoStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val service = MixedDenoService.get(project)
    val root = service.root ?: return
    val hasDeno = Workspace.scan(root).scopes.any { it.kind == ScopeKind.DENO }
    if (!hasDeno) return
    val denoPlugin = PluginManagerCore.getPlugin(PluginId.getId("deno"))
    if (denoPlugin != null && !PluginManagerCore.isDisabled(denoPlugin.pluginId)) {
      service.notify(
        "The bundled Deno plugin is enabled. Set Settings | Languages &amp; Frameworks | Deno to <b>Disable</b> for this project " +
          "so WebStorm's TypeScript service (with the generated tsconfig.deno.json files) handles every file.",
        NotificationType.INFORMATION,
      )
    }
    if (MixedDenoSettings.get(project).state.autoGenerate) service.schedule(force = true, delayMs = 3000)
  }
}
