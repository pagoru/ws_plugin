package dev.mixeddeno.core

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Same generation as the IDE plugin, from the command line (CI, other editors):
 *   ./gradlew generateTypes --args="/path/to/repo [--configure] [--deno /path/to/deno]"
 */
fun main(args: Array<String>) {
  val dir = args.firstOrNull { !it.startsWith("--") && args.getOrNull(args.indexOf(it) - 1) != "--deno" } ?: "."
  val denoArg = args.indexOf("--deno").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
  val root = Path.of(dir).toAbsolutePath().normalize()
  val deno = DenoProcess.locate(denoArg) ?: run { System.err.println("deno not found"); exitProcess(2) }
  if ("--configure" in args) Configurer(root).run().forEach { println("configure: $it") }
  val result = Generator(root, DenoProcess(deno)).run()
  if ("--configure" in args) Configurer(root).run {
    applyDualSplits(result.dualSplits)
    applyWebPaths(result.webPaths)
  }.forEach { println("configure: $it") }
  result.log.forEach { println(it) }
  result.problems.forEach { println("problem: ${root.relativize(Path.of(it.file))} '${it.specifier}': ${it.message}") }
  println("${result.written.size} file(s) written")
}
