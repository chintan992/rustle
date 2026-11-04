package com.chint.audiobook.ui.library

import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.PlaybackPosition

/**
 * UI Round 4 (R6): word-weighted book progress, computed purely from the book
 * manifest and the position sidecar:
 *
 *   fraction = (Σ words of chapters before the current;;
 *               + words of the current chapter × sentenceIndex/segmentCount)
 *              ÷ Σ words of all chapters
 *
 * Returns null whenever the data cannot represent an honest fraction (no book,
 * no position, unknown position chapter, zero total words) — the UI renders no
 * bar rather than a lying one. Chapter ordering uses [ChapterRef.index] values,
 * not list positions.
 */
object BookProgress {

  fun fraction(book: Book?, position: PlaybackPosition?): Float? {
    if (book == null || position == null) return null
    val totalWords = book.chapters.sumOf { it.wordCount }
    if (totalWords <= 0) return null
    val current = book.chapters.firstOrNull { it.index == position.chapterIndex } ?: return null
    val wordsBefore = book.chapters
      .filter { it.index < position.chapterIndex }
      .sumOf { it.wordCount }
    val sentenceFraction = if (position.segmentCount > 0) {
      (position.sentenceIndex.toFloat() / position.segmentCount).coerceIn(0f, 1f)
    } else {
      0f
    }
    val words = wordsBefore + current.wordCount * sentenceFraction
    return (words / totalWords).coerceIn(0f, 1f)
  }

  /** "Chapter 2 of 3 · 42%" using ChapterRef.index ordering; null when unknown. */
  fun summary(book: Book?, position: PlaybackPosition?): String? {
    if (book == null || position == null) return null
    val sorted = book.chapters.sortedBy { it.index }
    val positionIdx = sorted.indexOfFirst { it.index == position.chapterIndex }
    if (positionIdx == -1) return null
    val chapterLine = "Chapter ${positionIdx + 1} of ${sorted.size}"
    val percent = fraction(book, position) ?: return chapterLine
    return "$chapterLine · ${(percent * 100).toInt()}%"
  }

  /**
   * R2: the book with the most recently written position is "Continue
   * Listening"; null when no book has a position (fresh install).
   */
  fun mostRecentlyPlayed(slugs: List<String>, positions: Map<String, com.chint.audiobook.data.model.PlaybackPosition>): String? =
    positions.entries
      .filter { it.key in slugs }
      .maxByOrNull { it.value.updatedAt }
      ?.key
}
