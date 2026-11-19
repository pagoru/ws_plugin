package dev.mixeddeno.core

/** Extracts module specifiers from TS/JS source text without a full parser. */
object ImportScanner {
  private val patterns = listOf(
    Regex("""(?:^|[;\s}])(?:import|export)\s+(?:type\s+)?[^'"`;]*?\sfrom\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE),
    Regex("""(?:^|[;\s])import\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE),
    Regex("""\bimport\s*\(\s*['"]([^'"]+)['"]\s*[,)]"""),
    Regex("""^\s*///\s*<reference\s+(?:path|types)\s*=\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE),
  )

  private val blockComment = Regex("""/\*[\s\S]*?\*/""")
  private val lineComment = Regex("""(^|[^:"'`])//(?!/).*$""", RegexOption.MULTILINE)

  fun scan(source: String): List<String> {
    val clean = source.replace(blockComment, " ").replace(lineComment, "$1")
    val result = LinkedHashSet<String>()
    for (p in patterns) p.findAll(clean).forEach { result.add(it.groupValues[1]) }
    // `/// <reference>` was stripped with comments; scan raw text for it
    patterns.last().findAll(source).forEach { result.add(it.groupValues[1]) }
    return result.toList()
  }

  private val denoGlobal = Regex("""(?<![\w.$])Deno\.""")
  fun usesDenoGlobal(source: String): Boolean =
    denoGlobal.containsMatchIn(source.replace(blockComment, " ").replace(lineComment, "$1"))

  fun isRelative(spec: String) = spec.startsWith("./") || spec.startsWith("../") || spec.startsWith("/") || spec == "." || spec == ".."
  fun isExternal(spec: String) =
    spec.startsWith("jsr:") || spec.startsWith("npm:") || spec.startsWith("https://") || spec.startsWith("http://")
  fun isNode(spec: String) = spec.startsWith("node:")
}
