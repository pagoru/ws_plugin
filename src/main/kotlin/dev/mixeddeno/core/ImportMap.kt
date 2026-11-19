package dev.mixeddeno.core

import java.nio.file.Path

/**
 * Deno import map semantics: exact keys, trailing-slash prefix keys (longest wins), and the
 * Deno extension where a `jsr:`/`npm:` mapped key also covers `key/subpath`.
 */
class ImportMap(val baseDir: Path, val entries: Map<String, String>) {

  sealed interface Target
  data class External(val specifier: String) : Target
  data class Local(val path: Path) : Target

  fun resolve(spec: String): Target? {
    entries[spec]?.let { return toTarget(it, "") }
    var best: String? = null
    for (key in entries.keys) {
      if (key.endsWith("/") && spec.startsWith(key) && (best == null || key.length > best.length)) best = key
    }
    if (best != null) return toTarget(entries.getValue(best), spec.substring(best.length))
    for ((key, value) in entries) {
      if (!key.endsWith("/") && spec.startsWith("$key/") && (value.startsWith("jsr:") || value.startsWith("npm:"))) {
        return toTarget(value.trimEnd('/') + "/", spec.substring(key.length + 1))
      }
    }
    return null
  }

  private fun toTarget(value: String, suffix: String): Target {
    val full = value + suffix
    if (ImportScanner.isExternal(full) || ImportScanner.isNode(full)) return External(full)
    return Local(baseDir.resolve(full).normalize())
  }

  /** Prefix entries pointing at local directories, e.g. `modules/` -> `./src/modules/`. */
  fun localPrefixes(): Map<String, Path> = entries
    .filter { (k, v) -> k.endsWith("/") && !ImportScanner.isExternal(v) && !ImportScanner.isNode(v) }
    .mapValues { baseDir.resolve(it.value).normalize() }

  companion object {
    val EMPTY = ImportMap(Path.of("/"), emptyMap())
  }
}
