package dev.mixeddeno.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import dev.mixeddeno.core.ScopeKind
import dev.mixeddeno.core.Workspace

class MixedDenoStartup : ProjectActivity {
  companion object {
    const val UPDATE_URL = "https://github.com/pagoru/ws_plugin/releases/latest/download/updatePlugins.xml"
    private val LOG = logger<MixedDenoStartup>()
  }

  override suspend fun execute(project: Project) {
    registerUpdateRepository()
    val service = MixedDenoService.get(project)
    val root = service.root ?: return
    val hasDeno = Workspace.scan(root).scopes.any { it.kind == ScopeKind.DENO }
    if (!hasDeno) return
    disableBundledDeno(project, service)
    if (MixedDenoSettings.get(project).state.autoGenerate) service.schedule(force = true, delayMs = 2000)
  }

  /** Lets the IDE know where this plugin comes from, so updates arrive like any other plugin. */
  private fun registerUpdateRepository() {
    try {
      val hosts = UpdateSettings.getInstance().storedPluginHosts
      if (UPDATE_URL !in hosts) hosts.add(UPDATE_URL)
    } catch (e: Exception) {
      LOG.info("Could not register plugin repository", e)
    }
  }

  /**
   * The bundled Deno plugin routes every file under a deno.json to the Deno LSP when enabled.
   * Switch it off for this project (via reflection, the plugin is optional).
   */
  private fun disableBundledDeno(project: Project, service: MixedDenoService) {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId("deno")) ?: return
    if (PluginManagerCore.isDisabled(plugin.pluginId)) return
    try {
      val loader = plugin.pluginClassLoader ?: return
      val settingsClass = loader.loadClass("com.intellij.deno.DenoSettings")
      val settings = project.getService(settingsClass) ?: return
      val isUse = settingsClass.getMethod("isUseDeno").invoke(settings) as Boolean
      if (!isUse) return
      @Suppress("UNCHECKED_CAST")
      val useDeno = loader.loadClass("com.intellij.deno.UseDeno") as Class<out Enum<*>>
      val disable = useDeno.enumConstants.first { it.name == "DISABLE" }
      ApplicationManager.getApplication().invokeLater({
        try {
          settingsClass.getMethod("setUseDenoAndReload", useDeno).invoke(settings, disable)
          service.notify("Turned off the bundled Deno LSP for this project; WebStorm's TypeScript service now handles every file.", NotificationType.INFORMATION)
        } catch (e: Exception) {
          LOG.info(e)
        }
      }, project.disposed)
    } catch (e: Exception) {
      LOG.info("Could not change Deno settings", e)
      service.notify(
        "The bundled Deno plugin is enabled. Set Settings | Languages &amp; Frameworks | Deno (or JavaScript Runtime) to <b>Disable</b> for this project.",
        NotificationType.INFORMATION,
      )
    }
  }
}
