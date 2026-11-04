package com.chint.audiobook.data.repository

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WS3: per-book voice/speed overrides as books/<slug>/prefs.json — the same
 * atomic sidecar pattern as bookmarks/position. Resolution everywhere is
 * book pref → global setting. Mirrors the desktop book.json voice field
 * (pack interop can map onto this later).
 */
@Serializable
data class BookPrefs(
  val voice: String? = null,
  val speed: Float? = null,
  // Multi-voice: per-book dialogue voice override; null inherits the global.
  val dialogueVoice: String? = null
)

class BookPrefsRepository(private val booksDir: File) {

  private val json = Json { ignoreUnknownKeys = true }

  fun load(bookSlug: String): BookPrefs =
    runCatching {
      val file = fileFor(bookSlug)
      BackupMirror.restoreIfMissing(file, booksDir, KIND, bookSlug)
      if (!file.exists()) return BookPrefs()
      json.decodeFromString<BookPrefs>(file.readText())
    }.getOrDefault(BookPrefs())

  fun save(bookSlug: String, prefs: BookPrefs): Boolean =
    runCatching {
      val file = fileFor(bookSlug)
      file.parentFile?.mkdirs()
      val tmp = File(file.parent, file.name + ".tmp")
      val jsonText = json.encodeToString(prefs)
      tmp.writeText(jsonText)
      if (file.exists()) file.delete()
      val ok = tmp.renameTo(file)
      if (ok) BackupMirror.writeThrough(booksDir, KIND, bookSlug, jsonText)
      ok
    }.getOrDefault(false)

  private fun fileFor(bookSlug: String) = File(booksDir, "$bookSlug/prefs.json")

  private companion object {
    const val KIND = "prefs"
  }
}
