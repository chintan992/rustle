package com.chint.audiobook.playback

import com.chint.audiobook.data.model.Segment

/**
 * Round 10 (Audit task "out-of-order-render playlist ordering"): canonical-order
 * playlist geometry for partially cached chapters.
 *
 * The playlist may be assembled in ANY intermediate mixed state (some segment WAVs
 * already on disk, some still rendering, some only in the global cache). The
 * invariant this planner enforces for every such state:
 *
 *   The playlist is ALWAYS the one-item-per-unique-WAV projection of a contiguous
 *   prefix of the chapter's segments, in segment (canonical) order.
 *
 * Projection: duplicate sentences share one hash/WAV ("Duplicate sentences share one
 * hash/WAV and count once" — round-6 semantics behind the [chapterFullyAppended]
 * distinct-hash guard), so the playlist holds ONE item per distinct hash, at the
 * first segment that produced it. Consequence: playlist item p maps to segment index
 * `anchors[p]` (see [PlaylistAnchor]), and highlight/seek use that mapping — the
 * dup-segment highlight maps to the first occurrence (identical audio).
 *
 * The parts are pure; disk probing (does `<hash>.wav` exist with a non-zero
 * length?) is injected as a predicate lambda so JVM tests can model cached-set x
 * render-completion-order states exactly.
 *
 * Ordering is maintained WITHOUT any mid-playback playlist rebuild: the seed loads
 * only the contiguous cached anchor prefix, and every later append goes through
 * [frontierAppends] — a hash becomes appendable only after everything before it is
 * appended, and appends stop at the first not-yet-rendered gap. An on-disk-but-
 * beyond-the-gap tail WAV therefore waits instead of jumping the queue.
 */
data class PlaylistAnchor(
  /** Segment hash that owns the shared WAV. */
  val hash: String,
  /** Index of the FIRST segment with that hash (the canonical anchor). */
  val segmentIndex: Int
)

object PlaylistOrderPlanner {

  /**
   * One [PlaylistAnchor] per distinct segment hash, in first-occurrence order. This is
   * the full canonical order the playlist will eventually grow through (as renders
   * close gaps); [seedCount] / [frontierAppends] / [playlistPositionFor] all treat
   * it as the source of order.
   */
  fun distinctAnchors(segments: List<Segment>): List<PlaylistAnchor> {
    val seen = HashSet<String>(segments.size)
    val anchors = ArrayList<PlaylistAnchor>(segments.size)
    for ((index, segment) in segments.withIndex()) {
      if (seen.add(segment.hash)) {
        anchors.add(PlaylistAnchor(segment.hash, index))
      }
    }
    return anchors
  }

  /**
   * Length of the longest CONTIGUOUS anchor prefix whose WAVs are already on disk.
   * Stops at the first uncached anchor: a cached far-tail anchor that sits behind a
   * missing gap anchor is NOT seedable — seeding it early would play it before its
   * predecessors and break canonical order.
   */
  fun seedCount(anchors: List<PlaylistAnchor>, isCached: (String) -> Boolean): Int {
    var count = 0
    while (count < anchors.size && isCached(anchors[count].hash)) {
      count++
    }
    return count
  }

  /**
   * The run of anchors now appendable after [appendedCount] anchors are already in
   * the playlist: every anchor from [appendedCount] on whose WAV is on disk, stopping
   * at the first NOT-yet-rendered gap anchor. Empty when the next anchor is still
   * rendering (or everything is appended). Returned in canonical order so the caller
   * can hand each item to the player sequentially within one poll cycle.
   */
  fun frontierAppends(
    anchors: List<PlaylistAnchor>,
    appendedCount: Int,
    isCached: (String) -> Boolean
  ): List<PlaylistAnchor> {
    val start = appendedCount.coerceIn(0, anchors.size)
    val result = ArrayList<PlaylistAnchor>(4)
    var index = start
    while (index < anchors.size && isCached(anchors[index].hash)) {
      result.add(anchors[index])
      index++
    }
    return result
  }

  /**
   * Playlist position for a SEGMENT index (highlight -> item, or a user click ->
   * seek target).
   *
   * 1. Exact anchor hit wins: clicking the SECOND occurrence of a duplicated
   *    sentence must seek that sentence's actual WAV (held by the first
   *    occurrence), not the item before it.
   * 2. Otherwise the anchor whose [segIndex, nextAnchor) window contains the
   *    requested index — i.e. the nearest anchor at-or-before the request. For a
   *    request beyond the current frontier this is the LAST appended item, matching
   *    the old coerce-to-end behavior for future sentences.
   */
  fun playlistPositionFor(
    anchors: List<PlaylistAnchor>,
    appendedCount: Int,
    segmentIndex: Int,
    segmentHash: String?
  ): Int {
    val k = appendedCount.coerceIn(0, anchors.size)
    if (k == 0) return 0

    if (segmentHash != null) {
      for (p in 0 until k) {
        if (anchors[p].hash == segmentHash) return p
      }
    }
    var position = 0
    for (p in 0 until k) {
      if (anchors[p].segmentIndex <= segmentIndex) position = p else break
    }
    return position
  }
}
