package com.chint.audiobook.data.repository

import com.chint.audiobook.data.model.LedgerEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LedgerRepository {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  fun appendEvent(bookDir: File, event: LedgerEvent) {
    val ledgerFile = File(bookDir, "ledger.jsonl")
    val line = json.encodeToString(event) + "\n"
    ledgerFile.appendText(line)
  }

  fun readEvents(bookDir: File): List<LedgerEvent> {
    val ledgerFile = File(bookDir, "ledger.jsonl")
    if (!ledgerFile.exists()) return emptyList()

    return ledgerFile.useLines { lines ->
      lines.mapNotNull { line ->
        if (line.isBlank()) null
        else runCatching { json.decodeFromString<LedgerEvent>(line) }.getOrNull()
      }.toList()
    }
  }

  /**
   * WS0 (storage round): rendered-state is derived from the FILESYSTEM alone —
   * a segment is rendered iff its non-empty WAV exists in the chapter audio dir
   * or the voice-shared global cache. The old implementation parsed the entire
   * ledger.jsonl first (O(every render_done in the book) per chapter load — a
   * fully-rendered 3,220-chapter book re-parses ~100k+ lines on every open)
   * and then UNIONED this same filesystem scan, so the parse was pure cost:
   * its hash set was already filtered to WAVs that exist. The ledger keeps its
   * role as the desktop interop contract + audit log (render_done metadata,
   * RTF history); it is no longer a render-state index.
   */
  fun getRenderedSegmentHashes(bookDir: File, chapterIndex: Int): Set<String> {
    val chapterAudioDir = File(bookDir, "audio/ch${"%04d".format(chapterIndex)}")
    val globalCacheAudioDir = File(bookDir, "cache/audio")

    val hashes = mutableSetOf<String>()
    chapterAudioDir.listFiles()?.forEach { file ->
      if (file.extension == "wav" && file.length() > 0L) {
        hashes.add(file.nameWithoutExtension)
      }
    }
    globalCacheAudioDir.listFiles()?.forEach { file ->
      if (file.extension == "wav" && file.length() > 0L) {
        hashes.add(file.nameWithoutExtension)
      }
    }
    return hashes
  }

  /** WS0: direct filesystem probe — no ledger read on the hot path. */
  fun isSegmentRendered(bookDir: File, chapterIndex: Int, segmentHash: String): Boolean {
    val chapterAudioDir = File(bookDir, "audio/ch${"%04d".format(chapterIndex)}")
    val globalCacheAudioDir = File(bookDir, "cache/audio")

    val chFile = File(chapterAudioDir, "$segmentHash.wav")
    if (chFile.exists() && chFile.length() > 0L) return true

    val cacheFile = File(globalCacheAudioDir, "$segmentHash.wav")
    return cacheFile.exists() && cacheFile.length() > 0L
  }
}
