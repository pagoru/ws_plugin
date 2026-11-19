package dev.mixeddeno.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface DenoCli {
  /** `deno info --json` with no argument: cache locations. */
  fun cacheInfo(): JsonObject
  /** `deno info --json <file>` on a generated entry file (run without config). */
  fun graph(entry: Path): JsonObject
  /** `deno types` output. */
  fun types(): String
}

class DenoProcess(private val deno: String, private val timeoutSec: Long = 300) : DenoCli {

  private fun run(vararg args: String, cwd: Path? = null): String {
    val pb = ProcessBuilder(listOf(deno) + args).redirectErrorStream(false)
    pb.environment()["NO_COLOR"] = "1"
    pb.environment()["DENO_NO_UPDATE_CHECK"] = "1"
    if (cwd != null) pb.directory(cwd.toFile())
    val p = pb.start()
    val err = StringBuilder()
    val errThread = Thread { err.append(p.errorStream.bufferedReader().readText()) }.also { it.start() }
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw DenoException("deno ${args.joinToString(" ")} timed out")
    }
    errThread.join()
    if (p.exitValue() != 0) throw DenoException("deno ${args.joinToString(" ")} failed: ${err.toString().trim().take(2000)}")
    return out
  }

  override fun cacheInfo(): JsonObject =
    Json.parseObject(run("info", "--json")) ?: throw DenoException("unexpected deno info output")

  override fun graph(entry: Path): JsonObject =
    Json.parseObject(run("info", "--json", "--no-config", "--no-lock", entry.toString(), cwd = entry.parent))
      ?: throw DenoException("unexpected deno info output")

  override fun types(): String = run("types")

  companion object {
    /** Finds deno on PATH or in the usual install locations. */
    fun locate(configured: String?): String? {
      if (!configured.isNullOrBlank()) return configured
      val exe = if (System.getProperty("os.name").lowercase().contains("win")) "deno.exe" else "deno"
      System.getenv("PATH")?.split(java.io.File.pathSeparator)?.forEach {
        val f = Path.of(it, exe)
        if (Files.isExecutable(f)) return f.toString()
      }
      val home = System.getProperty("user.home")
      return listOf(Path.of(home, ".deno", "bin", exe), Path.of("/opt/homebrew/bin", exe), Path.of("/usr/local/bin", exe))
        .firstOrNull { Files.isExecutable(it) }?.toString()
    }
  }
}

class DenoException(message: String) : Exception(message)
