package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
  val slug: String,
  val title: String,
  val author: String,
  val language: String = "en",
  val coverPath: String? = null,
  val chapters: List<ChapterRef> = emptyList()
)

@Serializable
data class ChapterRef(
  val index: Int,
  val title: String,
  val prepared: String,
  val wordCount: Int = 0
)
