package com.chint.audiobook.data.repository

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Q4: dramatis personae — a manual per-book cast list
 * (books/<slug>/characters.json), the Smart AudioBook Player feature that
 * earns its keep on huge-cast serials. Mirrored through [BackupMirror] like
 * the other irreplaceable sidecars.
 */
@Serializable
data class BookCharacter(
  val name: String,
  val note: String = ""
)

class CharacterRepository(private val booksDir: File) {

  private val json = Json { ignoreUnknownKeys = true }

  fun load(bookSlug: String): List<BookCharacter> {
    val file = fileFor(bookSlug)
    BackupMirror.restoreIfMissing(file, booksDir, KIND, bookSlug)
    if (!file.exists()) return emptyList()
    return runCatching {
      json.decodeFromString<List<BookCharacter>>(file.readText())
    }.getOrDefault(emptyList())
  }

  fun save(bookSlug: String, characters: List<BookCharacter>): Boolean =
    runCatching {
      val file = fileFor(bookSlug)
      file.parentFile?.mkdirs()
      val tmp = File(file.parent, file.name + ".tmp")
      val jsonText = json.encodeToString(characters)
      tmp.writeText(jsonText)
      if (file.exists()) file.delete()
      val ok = tmp.renameTo(file)
      if (ok) BackupMirror.writeThrough(booksDir, KIND, bookSlug, jsonText)
      ok
    }.getOrDefault(false)

  private fun fileFor(bookSlug: String) = File(booksDir, "$bookSlug/characters.json")

  private companion object {
    const val KIND = "characters"
  }
}
