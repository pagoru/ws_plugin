package dev.mixeddeno.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

class MixedDenoState : BaseState() {
  var denoPath by string("")
  var autoGenerate by property(true)
  var autoConfigure by property(true)
}

@Service(Service.Level.PROJECT)
@State(name = "MixedDenoTypes", storages = [Storage("mixedDenoTypes.xml")])
class MixedDenoSettings : SimplePersistentStateComponent<MixedDenoState>(MixedDenoState()) {
  companion object {
    fun get(project: Project): MixedDenoSettings = project.getService(MixedDenoSettings::class.java)
  }
}
