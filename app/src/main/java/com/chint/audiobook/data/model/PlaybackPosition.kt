package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

/**
 * UI Round 4 (R6): per-book listening position sidecar
 * (`books/<slug>/position.json`). Mirrors the Bookmark record's role: a
 * UI-level persistence unit that never touches the render ledger.
 *
 * `segmentCount` is recorded at write time (the reader VM already has the
 * loaded chapter's segments) so the word-weighted progress formula needs no
 * per-chunk disk reads in the Library. `updatedAt` is the epoch-ms stamp used
 * to resolve "most recently played" for the Continue card.
 */
@Serializable
data class PlaybackPosition(
  val chapterIndex: Int,
  val sentenceIndex: Int,
  val offsetMs: Long = 0L,
  val segmentCount: Int = 0,
  val updatedAt: Long = 0L
)
