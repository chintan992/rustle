package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LedgerEvent(
  val event: String,
  val timestampMs: Long = System.currentTimeMillis(),
  val bookSlug: String,
  val chapterIndex: Int? = null,
  val segmentHash: String? = null,
  val voice: String? = null,
  val audioSeconds: Double? = null,
  val wallSeconds: Double? = null,
  val rtf: Double? = null,
  val reason: String? = null
)
