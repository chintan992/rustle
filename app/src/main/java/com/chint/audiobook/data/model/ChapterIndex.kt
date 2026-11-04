package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SegmentIndexEntry(
  val hash: String,
  val startMs: Long,
  val durationMs: Long
)

@Serializable
data class ChapterIndex(
  val chapterIndex: Int,
  val totalDurationMs: Long,
  val segments: List<SegmentIndexEntry>,
  val engine: TtsEngine? = null,
  val narrationVoice: String? = null,
  val dialogueVoice: String? = null,
  val engineVersion: String? = null
)
