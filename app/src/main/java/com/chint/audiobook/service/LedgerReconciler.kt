package com.chint.audiobook.service

import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.repository.LedgerRepository
import kotlinx.serialization.json.Json
import java.io.File

data class ReconciliationResult(
  val totalSegments: Int,
  val renderedSegments: Int,
  val pendingSegments: List<Segment>,
  val firstUnrenderedIndex: Int
)

class LedgerReconciler(private val ledgerRepository: LedgerRepository = LedgerRepository()) {

  private val json = Json { ignoreUnknownKeys = true }

  fun reconcileChapter(
    bookDir: File,
    chapterIndex: Int
  ): ReconciliationResult {
    val chapterSlug = "ch${"%04d".format(chapterIndex)}"
    val segmentsFile = File(bookDir, "chapters/$chapterSlug.segments.json")

    if (!segmentsFile.exists()) {
      return ReconciliationResult(0, 0, emptyList(), 0)
    }

    val segments = runCatching {
      json.decodeFromString<List<Segment>>(segmentsFile.readText())
    }.getOrDefault(emptyList())

    val renderedHashes = ledgerRepository.getRenderedSegmentHashes(bookDir, chapterIndex)
    val pending = mutableListOf<Segment>()
    var renderedCount = 0
    var firstUnrenderedIndex = -1

    for ((idx, seg) in segments.withIndex()) {
      val isRendered = renderedHashes.contains(seg.hash)
      if (isRendered) {
        renderedCount++
      } else {
        if (firstUnrenderedIndex == -1) {
          firstUnrenderedIndex = idx
        }
        pending.add(seg)
      }
    }

    return ReconciliationResult(
      totalSegments = segments.size,
      renderedSegments = renderedCount,
      pendingSegments = pending,
      firstUnrenderedIndex = if (firstUnrenderedIndex == -1) segments.size else firstUnrenderedIndex
    )
  }
}
