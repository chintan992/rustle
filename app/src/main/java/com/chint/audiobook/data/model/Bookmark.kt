package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

/**
 * ElevenReader plan (A2): a user bookmark. `label` is the auto-label — the
 * opening words of the bookmarked sentence — so the list is scannable without
 * extra UI state.
 */
@Serializable
data class Bookmark(
  val chapterIndex: Int,
  val sentenceIndex: Int,
  val offsetMs: Long = 0L,
  val label: String,
  val createdAt: Long
)
