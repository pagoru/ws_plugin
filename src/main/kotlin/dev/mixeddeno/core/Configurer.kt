package dev.mixeddeno.core

import com.google.gson.JsonObject
import dev.mixeddeno.core.Json.obj
import dev.mixeddeno.core.Json.str
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension

/**
 * One-time project setup: points each scope's own tsconfig.json at the generated config and
 * removes options that would override it. Comments in edited tsconfig.json files are not preserved.
 */
class Configurer(private val root: Path) {
  val changes = mutableListOf<String>()

  private val denoOwned = setOf(
    "lib", "types", "target", "typeRoots", "paths", "baseUrl", "moduleResolution", "module",
    "allowImportingTsExtensions", "noEmit",
  )

  /** @param web also adjust browser tsconfigs (include src, vite/client types, tsconfig.node.json). */
  fun run(web: Boolean = true): List<String> {
    val ws = Workspace.scan(root)
    for (s in ws.scopes) when (s.kind) {
      ScopeKind.DENO -> configureDeno(s)
      ScopeKind.WEB -> if (web) configureWeb(s)
    }
    if (ws.scopes.any { it.kind == ScopeKind.DENO }) hideFromGit()
    return changes
  }

  private val git = GitLocal(root)
  private val created = mutableListOf<Path>()
  private val edited = mutableListOf<Path>()

  /** Hide everything the plugin writes from git, locally (.git/info/exclude + skip-worktree). */
  private fun hideFromGit() {
    if (!git.available) return
    val patterns = mutableListOf("${Generator.OUT_DIR}/", Generator.DENO_TSCONFIG, Generator.UNIVERSAL_TSCONFIG)
    patterns += created.map { git.anchored(it) }
    if (git.exclude(patterns)) changes += "hid generated files from git (.git/info/exclude, not committed)"
    for (f in edited) if (git.isTracked(f) && git.skipWorktree(f)) changes += "git: ${rel(f)} marked --skip-worktree (your local edit is never committed)"
  }

  /**
   * Folders with deno.json + package.json: their tsconfig.json stays the package.json (browser/Node) config,
   * minus the Deno-only files, which go to tsconfig.deno.json. The user's original `exclude` is remembered
   * in .deno-types/state.json so the list can be recomputed.
   */
  fun applyDualSplits(splits: Map<Path, List<Path>>): List<String> {
    if (splits.isEmpty()) return changes
    val stateFile = root.resolve(Generator.OUT_DIR).resolve("state.json")
    val state = Json.readObject(stateFile) ?: JsonObject()
    for ((dir, denoFiles) in splits) {
      val file = dir.resolve("tsconfig.json")
      val key = rel(file)
      val denoRel = denoFiles.map { dir.relativize(it).toString().replace('\\', '/') }.sorted()
      var json = if (file.exists()) Json.readObject(file) else null
      if (json == null) {
        json = JsonObject().apply {
          add("compilerOptions", JsonObject().apply {
            addProperty("target", "esnext")
            addProperty("module", "esnext")
            addProperty("moduleResolution", "bundler")
            add("lib", Json.array(listOf("esnext", "DOM", "DOM.Iterable")))
            addProperty("jsx", "react-jsx")
            addProperty("allowImportingTsExtensions", true)
            addProperty("noEmit", true)
            addProperty("skipLibCheck", true)
            addProperty("isolatedModules", true)
            addProperty("strict", true)
          })
        }
        created.add(file)
      }
      // undo an older plugin version that made this tsconfig.json extend the Deno config
      val ext = json.get("extends")
      val deno = "./${Generator.DENO_TSCONFIG}"
      if (ext != null && (ext.isJsonPrimitive && ext.asString == deno || ext.isJsonArray && ext.asJsonArray.any { it.asString == deno })) {
        val original = git.headContent(file)?.let { Json.parseObject(it) }
        if (original != null) json = original
        else if (ext.isJsonArray) json.add("extends", Json.array(ext.asJsonArray.map { it.asString }.filter { it != deno }))
        else json.remove("extends")
      }
      val entry = state.obj(key) ?: JsonObject().also { e ->
        json.get("exclude")?.let { e.add("exclude", it) } ?: e.addProperty("noExclude", true)
        state.add(key, e)
      }
      val base = entry.get("exclude")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: listOf("node_modules")
      val newExclude = Json.array(base + denoRel.filter { it !in base })
      if (json.get("exclude") != newExclude || file !in created && !file.exists()) {
        json.add("exclude", newExclude)
        Json.write(file, json)
        if (file !in created) edited.add(file)
        changes += "${rel(file)}: ${denoRel.size} Deno file(s) moved to ${Generator.DENO_TSCONFIG}, the rest stays on package.json"
      } else if (file in created) {
        Json.write(file, json)
      }
    }
    Json.write(stateFile, state)
    hideFromGit()
    return changes
  }

  /** Adds `paths` for packages that shared code (outside the app) imports, pointing at the app's node_modules. */
  fun applyWebPaths(webPaths: Map<Path, Map<String, List<String>>>): List<String> {
    for ((config, entries) in webPaths) {
      if (entries.isEmpty()) continue
      val json = Json.readObject(config) ?: continue
      val co = json.obj("compilerOptions") ?: JsonObject().also { json.add("compilerOptions", it) }
      val paths = co.obj("paths") ?: JsonObject().also { co.add("paths", it) }
      val added = entries.filterKeys { !paths.has(it) }
      if (added.isEmpty()) continue
      for ((k, v) in added) paths.add(k, Json.array(v))
      Json.write(config, json)
      edited.add(config)
      changes += "${rel(config)}: paths for ${added.keys.filter { !it.endsWith("/*") }.joinToString()} (used by shared code) -> this app's node_modules"
    }
    hideFromGit()
    return changes
  }

  private fun rel(p: Path) = root.relativize(p).toString().replace('\\', '/')

  private fun configureDeno(scope: Scope) {
    if (scope.isDual) return // handled by applyDualSplits after generation
    val file = scope.dir.resolve("tsconfig.json")
    val extends = "./${Generator.DENO_TSCONFIG}"
    if (!file.exists()) {
      Json.write(file, JsonObject().apply { addProperty("extends", extends) })
      created.add(file)
      changes += "created ${rel(file)}"
      return
    }
    val json = Json.readObject(file) ?: return
    val current = json.get("extends")
    val currentList = when {
      current == null -> emptyList()
      current.isJsonArray -> current.asJsonArray.map { it.asString }
      else -> listOf(current.asString)
    }
    if (extends in currentList && json.obj("compilerOptions")?.keySet()?.none { it in denoOwned } != false) return
    val out = JsonObject()
    val others = currentList.filter { it != extends }
    if (others.isEmpty()) out.addProperty("extends", extends)
    else out.add("extends", Json.array(others + extends))
    val hasTsx = scope.files.any { it.extension == "tsx" }
    json.obj("compilerOptions")?.let { co ->
      val kept = JsonObject()
      for ((k, v) in co.entrySet()) {
        if (k in denoOwned) continue
        if (k == "jsx" && !hasTsx) continue
        kept.add(k, v)
      }
      if (kept.size() > 0) out.add("compilerOptions", kept)
    }
    json.get("exclude")?.let { out.add("exclude", it) }
    for ((k, v) in json.entrySet()) if (k !in setOf("extends", "compilerOptions", "include", "files", "exclude")) out.add(k, v)
    Json.write(file, out)
    edited.add(file)
    changes += "updated ${rel(file)} (extends $extends; removed lib/types/paths/baseUrl/module options)"
  }

  private fun configureWeb(scope: Scope) {
    val file = scope.config ?: return
    val json = Json.readObject(file) ?: return
    val pkg = Json.readObject(scope.dir.resolve("package.json"))
    val hasVite = listOf("dependencies", "devDependencies").any { pkg?.obj(it)?.has("vite") == true }
    var changed = false
    if (!json.has("include") && !json.has("files") && Files.isDirectory(scope.dir.resolve("src"))) {
      json.add("include", Json.array(listOf("src")))
      changed = true
    }
    val co = json.obj("compilerOptions") ?: JsonObject().also { json.add("compilerOptions", it) }
    if (hasVite && !co.has("types")) {
      co.add("types", Json.array(listOf("vite/client")))
      changed = true
    }
    if (changed) {
      Json.write(file, json)
      edited.add(file)
      changes += "updated ${rel(file)} (include src, vite/client types: no Node globals in browser code)"
    }
    val viteConfig = listOf("vite.config.ts", "vite.config.mts").map { scope.dir.resolve(it) }.firstOrNull { it.exists() }
    val nodeConfig = scope.dir.resolve("tsconfig.node.json")
    if (viteConfig != null && !nodeConfig.exists()) {
      Json.write(nodeConfig, JsonObject().apply {
        add("compilerOptions", JsonObject().apply {
          addProperty("target", "esnext")
          addProperty("module", "esnext")
          addProperty("moduleResolution", "bundler")
          add("types", Json.array(listOf("node")))
          addProperty("noEmit", true)
          addProperty("skipLibCheck", true)
        })
        add("include", Json.array(listOf(viteConfig.fileName.toString())))
      })
      created.add(nodeConfig)
      changes += "created ${rel(nodeConfig)}"
    }
  }
}
