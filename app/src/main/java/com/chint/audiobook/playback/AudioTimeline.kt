package com.chint.audiobook.playback

import com.chint.audiobook.data.model.ChapterIndex

/**
 * ElevenReader plan (A3): chapter-level audio timeline over the canonical
 * playlist projection. One entry per distinct hash anchor — the same
 * first-occurrence dedup order as [PlaylistOrderPlanner.distinctAnchors], so
 * entry i sits at playlist item i inside the canonical window.
 *
 * Cumulative starts let the reader scrubber target any wall-clock position of
 * the chapter and let both skip buttons resolve a ±second jump exactly, all in
 * playlist-item space — the same space [PlaybackController.seekToSentence] uses.
 *
 * Durations arrive from WAV headers ([WavDuration.durationMs]) as each WAV is
 * seen; an entry whose duration is still unknown carries 0 and is skipped by
 * seek resolution. Targets beyond the last known-audio end clamp there — the
 * same frontier semantics as [PlaylistOrderPlanner.playlistPositionFor] for
 * sentences beyond the append frontier.
 *
 * Pure parts are JVM-testable; durations are injected as a map.
 */
data class TimelineEntry(
  /** Index in the canonical anchors (== playlist item index in the canonical window). */
  val anchorIndex: Int,
  /** First segment index this item covers (dup sentences map to the first occurrence). */
  val segmentIndex: Int,
  val hash: String,
  /** WAV duration in ms; 0 until measured (unknown). */
  val durationMs: Long,
  /** Chapter offset of this item's start (sum of known durations before it). */
  val cumulativeStartMs: Long
)

object AudioTimeline {

  data class SeekPoint(val itemIndex: Int, val offsetMs: Long)

  /** Cumulative timeline over [anchors]; unknown hashes (not in the map) carry duration 0. */
  fun build(anchors: List<PlaylistAnchor>, durationsByHash: Map<String, Long>): List<TimelineEntry> {
    var cumulative = 0L
    return anchors.mapIndexed { anchorIndex, anchor ->
      val duration = durationsByHash[anchor.hash] ?: 0L
      val entry = TimelineEntry(anchorIndex, anchor.segmentIndex, anchor.hash, duration, cumulative)
      if (duration > 0) cumulative += duration
      entry
    }
  }

  /** Builds timeline entries directly from a compressed [ChapterIndex]. */
  fun buildFromIndex(index: ChapterIndex): List<TimelineEntry> {
    return index.segments.mapIndexed { idx, segment ->
      TimelineEntry(
        anchorIndex = idx,
        segmentIndex = idx,
        hash = segment.hash,
        durationMs = segment.durationMs,
        cumulativeStartMs = segment.startMs
      )
    }
  }

  /** Total KNOWN audio span (end of the last duration-bearing entry). */
  fun totalKnownDurationMs(entries: List<TimelineEntry>): Long {
    var end = 0L
    for (entry in entries) {
      if (entry.durationMs > 0) end = entry.cumulativeStartMs + entry.durationMs
    }
    return end
  }

  /** Chapter position of [itemIndex] with its in-item offset; 0 when not measureable yet. */
  fun positionOf(entries: List<TimelineEntry>, itemIndex: Int, positionInItemMs: Long): Long {
    val entry = entries.getOrNull(itemIndex) ?: return 0L
    if (entry.durationMs <= 0) return 0L
    return entry.cumulativeStartMs + positionInItemMs.coerceIn(0L, entry.durationMs)
  }

  /**
   * Resolves a chapter time target to playlist item + offset. The chosen entry
   * is the first whose known span contains the target; anything beyond the end
   * of known audio lands on the LAST known entry (frontier clamp). Returns null
   * when no entry has a measured duration (nothing to seek into yet).
   */
  fun resolveSeek(entries: List<TimelineEntry>, targetMs: Long): SeekPoint? {
    if (totalKnownDurationMs(entries) <= 0L) return null
    val clamped = targetMs.coerceIn(0L, totalKnownDurationMs(entries))
    var chosen: TimelineEntry? = null
    for (entry in entries) {
      if (entry.durationMs <= 0) continue
      if (clamped < entry.cumulativeStartMs + entry.durationMs) {
        chosen = entry
        break
      }
    }
    val entry = chosen ?: entries.last { it.durationMs > 0 }
    val offset = (clamped - entry.cumulativeStartMs).coerceIn(0L, entry.durationMs - 1)
    return SeekPoint(entry.anchorIndex, offset)
  }
}
