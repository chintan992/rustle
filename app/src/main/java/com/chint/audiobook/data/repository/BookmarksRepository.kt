package com.chint.audiobook.data.repository

import com.chint.audiobook.data.model.Bookmark
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ElevenReader plan (A2): per-book bookmark persistence as
 * `books/<slug>/bookmarks.json` — a UI-level sidecar that never touches the
 * render ledger. Writes are atomic-shaped (temp file + rename) mirroring the
 * pipeline's atomic-output rule. Corrupt/missing files read as empty; the next
 * save rewrites a good file.
 */
class BookmarksRepository(private val booksDir: File) {

  private val json = Json { ignoreUnknownKeys = true }

  fun load(bookSlug: String): List<Bookmark> {
    val file = fileFor(bookSlug)
    // Q1: fresh install + cloud restore — the primary rides outside the
    // backup quota, the mirror rides inside it.
    BackupMirror.restoreIfMissing(file, booksDir, KIND, bookSlug)
    if (!file.exists()) return emptyList()
    return runCatching {
      json.decodeFromString<List<Bookmark>>(file.readText())
    }.getOrDefault(emptyList())
  }

  fun save(bookSlug: String, bookmarks: List<Bookmark>): Boolean {
    return runCatching {
      val file = fileFor(bookSlug)
      file.parentFile?.mkdirs()
      val tmp = File(file.parent, file.name + ".tmp")
      val jsonText = json.encodeToString(bookmarks)
      tmp.writeText(jsonText)
      if (file.exists()) {
        file.delete()
      }
      val ok = tmp.renameTo(file)
      if (ok) BackupMirror.writeThrough(booksDir, KIND, bookSlug, jsonText)
      ok
    }.getOrDefault(false)
  }

  private fun fileFor(bookSlug: String): File = File(booksDir, "$bookSlug/bookmarks.json")

  private companion object {
    const val KIND = "bookmarks"
  }
}
