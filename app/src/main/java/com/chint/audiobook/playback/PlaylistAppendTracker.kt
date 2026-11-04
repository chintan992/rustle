package com.chint.audiobook.playback

class PlaylistAppendTracker {
  private val appendedHashes = mutableSetOf<String>()

  fun isAppended(hash: String): Boolean = appendedHashes.contains(hash)

  fun markAppended(hash: String) {
    appendedHashes.add(hash)
  }

  fun clear() {
    appendedHashes.clear()
  }

  fun size(): Int = appendedHashes.size

  /**
   * Round 10 (Task 1): point-in-time copy of the appended hash set. The caller may
   * keep iterating while appends continue elsewhere (e.g. tests that prove the
   * frontier consumes snapshots without seeing later marks).
   */
  fun snapshot(): Set<String> = appendedHashes.toSet()
}
