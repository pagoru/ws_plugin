package dev.mixeddeno.core

import com.google.gson.JsonObject
import dev.mixeddeno.core.Json.obj
import dev.mixeddeno.core.Json.str
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

enum class ScopeKind { DENO, WEB }

/**
 * A directory that owns a type environment.
 * DENO: a deno.json(c) with an import map / compilerOptions (task-only configs are ignored).
 * WEB: a package.json next to a tsconfig.json (browser / Node app, e.g. a Vite client).
 */
class Scope(val kind: ScopeKind, val dir: Path, val config: Path?, val importMap: ImportMap, val denoJson: JsonObject?) {
  val files = mutableListOf<Path>()

  /** package.json in the same folder (Deno also resolves bare imports through it). */
  val packageJson: JsonObject? = dir.resolve("package.json").takeIf { it.exists() }?.let { Json.readObject(it) }

  /** A Deno scope that also has a package.json: browser/Node files (.tsx, no Deno APIs) live next to Deno files. */
  val isDual: Boolean get() = kind == ScopeKind.DENO && packageJson != null

  val npmDeps: Set<String> by lazy {
    val pkg = packageJson ?: return@lazy emptySet()
    listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")
      .flatMap { pkg.obj(it)?.keySet().orEmpty() }.toSet()
  }

  /** True if [spec] is a package from package.json / node_modules of this folder. */
  fun isPackageImport(spec: String): Boolean {
    if (packageJson == null) return false
    val name = if (spec.startsWith("@")) spec.split('/').take(2).joinToString("/") else spec.substringBefore('/')
    return name in npmDeps || dir.resolve("node_modules").resolve(name).exists() ||
      (!name.startsWith("@types/") && dir.resolve("node_modules/@types").resolve(name.removePrefix("@").replace('/', '_').let { if (name.startsWith("@")) it.replaceFirst("_", "__") else it }).exists())
  }
  override fun toString() = "$kind:$dir"
}

class Workspace(val root: Path) {
  val scopes = mutableListOf<Scope>()

  companion object {
    val SKIP_DIRS = setOf("node_modules", ".git", ".idea", ".deno-types", "vendor", ".yarn", ".vite", "dist", "build", "out", "coverage", ".gradle")
    val SOURCE_EXT = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs")
    val DENO_CONFIG = listOf("deno.json", "deno.jsonc")

    fun scan(root: Path): Workspace = Workspace(root.toAbsolutePath().normalize()).also { it.walk(it.root, null) }

    fun readImportMap(dir: Path, deno: JsonObject): ImportMap {
      val entries = LinkedHashMap<String, String>()
      var base = dir
      deno.str("importMap")?.let { rel ->
        val file = dir.resolve(rel).normalize()
        Json.readObject(file)?.obj("imports")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) entries[k] = v.asString }
        base = file.parent
      }
      deno.obj("imports")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) entries[k] = v.asString }
      return ImportMap(base, entries)
    }
  }

  private fun isDenoScope(dir: Path, deno: JsonObject): Boolean {
    if (deno.obj("imports")?.size()?.let { it > 0 } == true) return true
    if (deno.has("importMap") || deno.has("compilerOptions")) return true
    // task-only config: still a Deno scope if it sits next to an entry file and no package.json
    return !dir.resolve("package.json").exists() && listOf("mod.ts", "main.ts").any { dir.resolve(it).exists() }
  }

  private fun walk(dir: Path, owner: Scope?) {
    var current = owner
    val denoFile = DENO_CONFIG.map { dir.resolve(it) }.firstOrNull { it.exists() }
    val deno = denoFile?.let { Json.readObject(it) }
    if (denoFile != null && deno != null && isDenoScope(dir, deno)) {
      current = Scope(ScopeKind.DENO, dir, denoFile, readImportMap(dir, deno), deno).also { scopes += it }
    } else if (dir.resolve("package.json").exists() && dir.resolve("tsconfig.json").exists()) {
      current = Scope(ScopeKind.WEB, dir, dir.resolve("tsconfig.json"), ImportMap.EMPTY, null).also { scopes += it }
    }
    val children = try { Files.list(dir).use { it.toList() } } catch (_: Exception) { return }
    for (child in children.sorted()) {
      if (child.isDirectory()) {
        if (child.name in SKIP_DIRS || Files.isSymbolicLink(child)) continue
        walk(child, current)
      } else if (current != null && child.extension in SOURCE_EXT && !child.name.endsWith(".d.ts")) {
        current.files.add(child)
      } else if (current != null && child.name.endsWith(".d.ts")) {
        current.files.add(child)
      }
    }
  }

  fun scopeOf(file: Path): Scope? = scopes.filter { file.startsWith(it.dir) }.maxByOrNull { it.dir.nameCount }
}
