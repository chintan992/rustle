package com.chint.audiobook.data.repository

import com.chint.audiobook.data.model.PlaybackPosition
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * UI Round 4 (R6): per-book playback position persistence as
 * `books/<slug>/position.json` — the same sidecar pattern as the bookmarks
 * repository (never touches the render ledger; temp file + rename write).
 * Missing/corrupt files read as null; the next save rewrites a good file.
 */
class PlaybackPositionRepository(private val booksDir: File) {

  private val json = Json { ignoreUnknownKeys = true }

  /** Latest saved position of [bookSlug], or null when never played/corrupt. */
  fun load(bookSlug: String): PlaybackPosition? {
    val file = fileFor(bookSlug)
    BackupMirror.restoreIfMissing(file, booksDir, KIND, bookSlug)
    if (!file.exists()) return null
    return runCatching {
      json.decodeFromString<PlaybackPosition>(file.readText())
    }.getOrNull()
  }

  /** Atomic-shaped save: write to a temp file, then rename over the target. */
  fun save(bookSlug: String, position: PlaybackPosition): Boolean {
    return runCatching {
      val file = fileFor(bookSlug)
      file.parentFile?.mkdirs()
      val tmp = File(file.parent, file.name + ".tmp")
      val jsonText = json.encodeToString(position)
      tmp.writeText(jsonText)
      if (file.exists()) {
        file.delete()
      }
      val ok = tmp.renameTo(file)
      if (ok) BackupMirror.writeThrough(booksDir, KIND, bookSlug, jsonText)
      ok
    }.getOrDefault(false)
  }

  fun delete(bookSlug: String): Boolean {
    // Q1: deletion removes the mirror too — a deleted book leaves no traces
    // (re-import starts fresh rather than resurrecting the old position).
    runCatching {
      File(BackupMirror.mirrorDir(booksDir, KIND), "$bookSlug.json").delete()
    }
    return runCatching { fileFor(bookSlug).delete() }.getOrDefault(false)
  }

  private fun fileFor(bookSlug: String): File = File(booksDir, "$bookSlug/position.json")

  private companion object {
    const val KIND = "positions"
  }
}
