package dev.mixeddeno.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class RegenerateAction : AnAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    MixedDenoService.get(project).generate(interactive = true)
  }
}

class ConfigureAction : AnAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val ok = Messages.showOkCancelDialog(
      project,
      "This makes every Deno scope's tsconfig.json extend the generated tsconfig.deno.json (creating it if missing), " +
        "removes lib/types/paths/baseUrl from them, and limits browser tsconfigs to src with vite/client types.\n\n" +
        "Comments inside edited tsconfig.json files are not kept. Continue?",
      "Configure tsconfig Files", "Configure", "Cancel", null,
    )
    if (ok == Messages.OK) MixedDenoService.get(project).configureTsconfigs()
  }
}
