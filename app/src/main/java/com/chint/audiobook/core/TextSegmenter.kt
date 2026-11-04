package com.chint.audiobook.core

import com.chint.audiobook.data.model.Segment

object TextSegmenter {
  private const val MIN_CHARS = 40
  private const val MAX_CHARS = 300

  fun segmentText(
    text: String,
    voice: String,
    engineId: String = SegmentHasher.DEFAULT_ENGINE_ID,
    modelDigest: String = SegmentHasher.DEFAULT_MODEL_DIGEST,
    language: String = "en"
  ): List<Segment> {
    val paragraphs = text.split(Regex("\r?\n\r?\n+"))
    val rawChunks = mutableListOf<String>()
    // UI Round 4 (R4): display metadata parallel to rawChunks. Chunk text is
    // never touched by these flags, so segment hashes stay identical.
    val chunkStartsParagraph = mutableListOf<Boolean>()
    val chunkIsSceneBreak = mutableListOf<Boolean>()

    fun addChunk(chunk: String, startsParagraph: Boolean, sceneBreak: Boolean = false) {
      rawChunks.add(chunk)
      chunkStartsParagraph.add(startsParagraph)
      chunkIsSceneBreak.add(sceneBreak)
    }

    for (p in paragraphs) {
      val trimmedP = p.trim()
      if (trimmedP.isEmpty()) continue

      // Check for scene break
      if (trimmedP == "***" || trimmedP == "---" || trimmedP == "...") {
        addChunk(trimmedP, startsParagraph = false, sceneBreak = true)
        continue
      }

      // Split paragraph into sentences
      val sentences = splitIntoSentences(trimmedP)
      var currentChunk = StringBuilder()
      var firstChunkOfParagraph = true

      for (sentence in sentences) {
        val s = sentence.trim()
        if (s.isEmpty()) continue

        if (s.length > MAX_CHARS) {
          // Flush current chunk if any
          if (currentChunk.isNotEmpty()) {
            addChunk(currentChunk.toString().trim(), firstChunkOfParagraph)
            firstChunkOfParagraph = false
            currentChunk = StringBuilder()
          }
          // Split long sentence at clause boundaries
          val clauses = splitLongSentence(s)
          clauses.forEachIndexed { idx, clause ->
            addChunk(clause, firstChunkOfParagraph && idx == 0)
          }
          firstChunkOfParagraph = false
          continue
        }

        // If adding next sentence fits under MAX_CHARS, merge it
        if (currentChunk.length + s.length + 1 <= MAX_CHARS) {
          if (currentChunk.isNotEmpty()) currentChunk.append(" ")
          currentChunk.append(s)
        } else {
          // Flush current chunk
          if (currentChunk.isNotEmpty()) {
            addChunk(currentChunk.toString().trim(), firstChunkOfParagraph)
            firstChunkOfParagraph = false
            currentChunk = StringBuilder()
          }
          currentChunk.append(s)
        }
      }

      if (currentChunk.isNotEmpty()) {
        // Enforce MIN_CHARS merge floor if possible with previous chunk.
        // UI Round 4 (R4): a merge swallows this paragraph's start (its text
        // rides on the previous chunk), so the merged chunk keeps the PREVIOUS
        // chunk's flag and the tiny paragraph's own start flag is dropped.
        // Scene-break chunks are never merge targets — that also closes the
        // old gap where "..." was mergeable while "***"/"---" were not.
        val chunkStr = currentChunk.toString().trim()
        if (chunkStr.length < MIN_CHARS && rawChunks.isNotEmpty()) {
          val lastIdx = rawChunks.size - 1
          val prevChunk = rawChunks[lastIdx]
          if (!chunkIsSceneBreak[lastIdx] && prevChunk.length + chunkStr.length + 1 <= MAX_CHARS) {
            rawChunks[lastIdx] = "$prevChunk $chunkStr"
          } else {
            addChunk(chunkStr, firstChunkOfParagraph)
          }
        } else {
          addChunk(chunkStr, firstChunkOfParagraph)
        }
      }
    }

    // Convert raw chunks to Segments with hashes
    return rawChunks.mapIndexed { index, chunkText ->
      val tags = extractTags(chunkText)
      val hash = SegmentHasher.computeHash(chunkText, voice, engineId, modelDigest, language = language)
      Segment(
        i = index + 1,
        hash = hash,
        text = chunkText,
        chars = chunkText.length,
        tags = tags,
        source = "s${"%04d".format(index + 1)}",
        startsParagraph = chunkStartsParagraph[index],
        isSceneBreak = chunkIsSceneBreak[index]
      )
    }
  }

  private fun splitIntoSentences(text: String): List<String> {
    val regex = Regex("(?<=[.!?\u06D4\u061F\u0964\u3002\uFF01\uFF1F])\\s+(?=[\\p{L}\\p{N}\"'<«»¿¡])")
    return text.split(regex)
  }

  private fun splitLongSentence(sentence: String): List<String> {
    val result = mutableListOf<String>()
    val parts = sentence.split(Regex("(?<=[,;:])\\s+"))
    var current = StringBuilder()

    for (part in parts) {
      if (current.length + part.length + 1 <= MAX_CHARS) {
        if (current.isNotEmpty()) current.append(" ")
        current.append(part)
      } else {
        if (current.isNotEmpty()) {
          result.add(current.toString().trim())
          current = StringBuilder()
        }
        if (part.length > MAX_CHARS) {
          part.chunked(MAX_CHARS).forEach { result.add(it.trim()) }
        } else {
          current.append(part)
        }
      }
    }
    if (current.isNotEmpty()) {
      result.add(current.toString().trim())
    }
    return result
  }

  private fun extractTags(text: String): List<String> {
    val tagRegex = Regex("<([a-z_]+)>")
    return tagRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
  }
}
