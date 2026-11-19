package dev.mixeddeno.core

import dev.mixeddeno.core.Json.obj
import dev.mixeddeno.core.Json.str
import java.nio.file.Path

/** Resolves imports of a WEB scope through its tsconfig `paths` / `baseUrl` (and `extends` chain). */
class WebResolver(private val scope: Scope, config: Path = scope.config!!) {
  private val patterns = mutableListOf<Pair<String, List<Path>>>()

  init {
    load(config, 0)
  }

  private fun load(config: Path, depth: Int) {
    if (depth > 5) return
    val json = Json.readObject(config) ?: return
    val co = json.obj("compilerOptions")
    val base = co?.str("baseUrl")?.let { config.parent.resolve(it).normalize() } ?: config.parent
    co?.obj("paths")?.entrySet()?.forEach { (key, value) ->
      if (patterns.none { it.first == key } && value.isJsonArray) {
        patterns += key to value.asJsonArray.map { base.resolve(it.asString).normalize() }
      }
    }
    json.str("extends")?.takeIf { it.startsWith(".") }?.let { load(config.parent.resolve(it).normalize(), depth + 1) }
  }

  /** Candidate files for a non-relative specifier. */
  fun resolve(spec: String): List<Path> {
    for ((key, targets) in patterns) {
      if (key.endsWith("*")) {
        val prefix = key.dropLast(1)
        if (spec.startsWith(prefix)) {
          val rest = spec.substring(prefix.length)
          return targets.map { Path.of(it.toString().replace("*", rest)).normalize() }
        }
      } else if (key == spec) return targets
    }
    return emptyList()
  }
}
