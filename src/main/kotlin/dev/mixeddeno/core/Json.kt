package dev.mixeddeno.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

object Json {
  val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  /** Parses JSON or JSONC (comments + trailing commas). Returns null on failure. */
  fun readObject(file: Path): JsonObject? = try {
    parseObject(Files.readString(file))
  } catch (_: Exception) {
    null
  }

  fun parseObject(text: String): JsonObject? = try {
    JsonParser.parseString(stripJsonc(text)).takeIf { it.isJsonObject }?.asJsonObject
  } catch (_: Exception) {
    null
  }

  fun write(file: Path, json: JsonElement): Boolean {
    val text = gson.toJson(json) + "\n"
    if (Files.exists(file) && Files.readString(file) == text) return false
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
    return true
  }

  fun stripJsonc(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    var inString = false
    while (i < text.length) {
      val c = text[i]
      if (inString) {
        out.append(c)
        if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i += 2; continue }
        if (c == '"') inString = false
        i++
        continue
      }
      when {
        c == '"' -> { inString = true; out.append(c); i++ }
        c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
          while (i < text.length && text[i] != '\n') i++
        }
        c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
          i += 2
          while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
          i += 2
        }
        c == ',' -> {
          var j = i + 1
          while (j < text.length && text[j].isWhitespace()) j++
          if (j < text.length && (text[j] == '}' || text[j] == ']')) i++ else { out.append(c); i++ }
        }
        else -> { out.append(c); i++ }
      }
    }
    return out.toString()
  }

  fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
  fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
  fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

  fun array(values: Iterable<String>): JsonArray = JsonArray().also { a -> values.forEach { a.add(it) } }
}
