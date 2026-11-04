package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackState(
  val bookSlug: String = "",
  val chapterIndex: Int = 1,
  val sentenceIndex: Int = 0,
  val offsetMs: Long = 0L,
  val voice: String = "F2",
  val speed: Float = 1.0f
)
