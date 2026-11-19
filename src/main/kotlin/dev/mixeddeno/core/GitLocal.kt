package dev.mixeddeno.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Keeps the plugin's changes invisible to git, locally only:
 * generated / created files go to .git/info/exclude, edits to tracked files use --skip-worktree.
 * Nothing here is committed, so other developers never see these changes.
 */
class GitLocal(private val root: Path) {

  private fun git(vararg args: String): Pair<Int, String>? = try {
    val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else p.exitValue() to out.trim()
  } catch (_: Exception) {
    null
  }

  val available: Boolean by lazy { git("rev-parse", "--is-inside-work-tree")?.second == "true" }

  private val topLevel: Path? by lazy { git("rev-parse", "--show-toplevel")?.takeIf { it.first == 0 }?.second?.let { Path.of(it) } }

  fun isTracked(file: Path): Boolean = git("ls-files", "--error-unmatch", file.toString())?.first == 0

  /** Hide local edits of a tracked file from `git status` / commits. */
  fun skipWorktree(file: Path): Boolean = git("update-index", "--skip-worktree", file.toString())?.first == 0

  /** Add patterns to .git/info/exclude (like .gitignore, but never committed). */
  fun exclude(patterns: List<String>): Boolean {
    val rel = git("rev-parse", "--git-path", "info/exclude")?.takeIf { it.first == 0 }?.second ?: return false
    val file = Path.of(rel).let { if (it.isAbsolute) it else root.resolve(it) }
    val text = if (file.exists()) Files.readString(file) else ""
    val lines = text.lines().map { it.trim() }.toSet()
    val missing = patterns.filter { it !in lines }
    if (missing.isEmpty()) return false
    Files.createDirectories(file.parent)
    val prefix = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
    Files.writeString(file, text + prefix + "# Mixed Deno Types (local only)\n" + missing.joinToString("\n") + "\n")
    return true
  }

  /** Path relative to the repository root, with a leading slash (anchored pattern for info/exclude). */
  fun anchored(file: Path): String {
    val top = topLevel ?: root
    return "/" + top.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
  }
}
