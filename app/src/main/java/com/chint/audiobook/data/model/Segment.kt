package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Segment(
  val i: Int,
  val hash: String,
  val text: String,
  val chars: Int,
  val tags: List<String> = emptyList(),
  val source: String = "",
  // UI Round 4 (R4): display-only metadata — never fed into SegmentHasher.
  // Defaults keep pre-R4 prepared chapter JSONs decoding (reader side uses
  // ignoreUnknownKeys; all-false means the reader falls back to card-row mode).
  val startsParagraph: Boolean = false,
  val isSceneBreak: Boolean = false
)
