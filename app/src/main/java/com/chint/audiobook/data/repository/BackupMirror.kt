package com.chint.audiobook.data.repository

import java.io.File

/**
 * Q1 (backup round): Android auto-backup has a ~25 MB per-app quota and its
 * rules support no wildcards — `books/<slug>/audio` (multi-GB) cannot be
 * excluded per book, and one audio file would eat the budget that sidecars
 * need. Strategy: backup rules EXCLUDE the heavy trees (`books`, `models`)
 * entirely, and the tiny irreplaceable sidecars (bookmarks / position /
 * book prefs) additionally MIRROR into files/backup/<kind>/<slug>.json,
 * which rides inside the quota. On load, a missing primary sidecar is
 * restored from its mirror (fresh install + cloud restore flow).
 *
 * Mirrors are plain copies, written after every successful primary save.
 */
object BackupMirror {

  fun mirrorDir(booksDir: File, kind: String): File =
    File(booksDir.parentFile, "backup/$kind")

  /** Write-through mirror; failures are silent (mirror is best-effort). */
  fun writeThrough(booksDir: File, kind: String, slug: String, json: String) {
    runCatching {
      val file = File(mirrorDir(booksDir, kind), "$slug.json")
      file.parentFile?.mkdirs()
      val tmp = File(file.parent, file.name + ".tmp")
      tmp.writeText(json)
      if (file.exists()) file.delete()
      tmp.renameTo(file)
    }
  }

  /**
   * Restore helper: when the primary sidecar is missing but the mirror has
   * one, copy it back and return true (caller then loads the primary).
   */
  fun restoreIfMissing(primary: File, booksDir: File, kind: String, slug: String): Boolean {
    if (primary.exists()) return false
    val mirror = File(mirrorDir(booksDir, kind), "$slug.json")
    if (!mirror.exists()) return false
    return runCatching {
      primary.parentFile?.mkdirs()
      mirror.copyTo(primary, overwrite = true)
      true
    }.getOrDefault(false)
  }
}
