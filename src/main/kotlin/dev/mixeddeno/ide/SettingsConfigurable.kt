package dev.mixeddeno.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class SettingsConfigurable(private val project: Project) : BoundConfigurable("Mixed Deno Types") {
  override fun createPanel(): DialogPanel {
    val state = MixedDenoSettings.get(project).state
    return panel {
      row("Deno executable:") {
        textField().columns(40).bindText({ state.denoPath ?: "" }, { state.denoPath = it })
          .comment("Leave empty to use deno from PATH or ~/.deno/bin")
      }
      row {
        checkBox("Regenerate types automatically when deno.json, tsconfig.json or imports change")
          .bindSelected({ state.autoGenerate }, { state.autoGenerate = it })
      }
    }
  }

  override fun apply() {
    super.apply()
    MixedDenoService.get(project).schedule(force = true, delayMs = 100)
  }
}
