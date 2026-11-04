package com.chint.audiobook.engine

import java.io.File

/**
 * Multi-voice round: the single source of truth for per-segment voice
 * assignment and the voice-aware WAV filename contract.
 *
 * Classification (v1, no LLM): a segment is DIALOGUE when the majority of its
 * text sits inside quotation marks — straight `"`, curly `“”`, or apostrophe
 * singles `'` are NOT counted (apostrophes are contractions, not quotes).
 * Narration attribution tails ("he said") simply pull the ratio down, so a
 * mostly-quote segment with a short tag still classifies as dialogue while a
 * narration sentence mentioning one quoted word does not.
 *
 * Filename contract (desktop pack interop preserved):
 *  - narration segments: `<hash>.wav` — UNCHANGED from the original contract,
 *    so desktop-rendered packs and existing caches keep working untouched;
 *  - dialogue segments: `<hash>~<voice>.wav` — the tilde keeps the file
 *    distinguishable from narration output while remaining scanner-safe
 *    (no scanner treats `~` as an extension separator). Changing the dialogue
 *    voice therefore targets a NEW filename: stale-voice files become orphans
 *    that eviction reclaims with their chapter, never wrong audio.
 *
 * The engine takes `sid` per generate() call (voice.bin holds all ten voices;
 * the model loads once), so alternating voices per segment costs nothing.
 */
object SegmentVoices {

  /** A segment whose text is majority-quoted speech. */
  const val DIALOGUE = true

  /** A segment that is narration (or not recognizably dialogue). */
  const val NARRATION = false

  /**
   * Quote-ratio threshold: quotedChars / totalChars must reach this for the
   * segment to render in the dialogue voice. 0.45 lets a quote with a short
   * "she said" tail through while excluding a narration sentence that quotes
   * one word.
   */
  const val DIALOGUE_RATIO_THRESHOLD = 0.45f

  /**
   * True when [dialogueVoice] is configured (multi-voice enabled). Everything
   * funnels through this so callers can't forget the disabled case.
   */
  fun multiVoiceEnabled(dialogueVoice: String?): Boolean =
    !dialogueVoice.isNullOrBlank()

  /**
   * Classify a prepared segment. Blank text or no complete quote pair is
   * narration. Counted quotes: the character span between a straight `"…"`
   * or curly `“…”` pair.
   */
  fun isDialogue(text: String): Boolean {
    if (text.isBlank()) return NARRATION
    val quotedChars = countQuotedChars(text)
    if (quotedChars == 0) return NARRATION
    return quotedChars.toFloat() / text.length.toFloat() >= DIALOGUE_RATIO_THRESHOLD
  }

  /** Total characters sitting inside complete straight or curly quote pairs. */
  internal fun countQuotedChars(text: String): Int {
    var total = 0
    for (pair in listOf('"' to '"', '“' to '”')) {
      var idx = text.indexOf(pair.first)
      while (idx >= 0) {
        val close = text.indexOf(pair.second, idx + 1)
        if (close < 0) break // unclosed quote: not counted (cannot know the span)
        total += close - idx - 1
        idx = text.indexOf(pair.first, close + 1)
      }
    }
    return total
  }

  /**
   * The voice a segment renders in: the dialogue voice for dialogue segments
   * when multi-voice is on, the given narration voice otherwise.
   */
  fun voiceFor(text: String, narrationVoice: String, dialogueVoice: String?): String =
    if (multiVoiceEnabled(dialogueVoice) && isDialogue(text)) dialogueVoice!! else narrationVoice

  /**
   * WAV file name for a segment under the contract documented in the class
   * KDoc. [dialogueVoice] only matters for dialogue segments.
   */
  fun wavFileName(hash: String, isDialogue: Boolean, dialogueVoice: String?): String =
    if (isDialogue && multiVoiceEnabled(dialogueVoice)) {
      "$hash~$dialogueVoice.wav"
    } else {
      "$hash.wav"
    }

  /**
   * The one WAV resolver: chapter audio dir first, then the per-book shared
   * cache — with the correct filename for the segment's classified role.
   */
  fun findWav(
    chapterAudioDir: File,
    globalCacheAudioDir: File,
    hash: String,
    text: String,
    dialogueVoice: String?
  ): File? {
    val isDialogue = isDialogue(text)
    val name = wavFileName(hash, isDialogue, dialogueVoice)
    val chapterFile = File(chapterAudioDir, name)
    if (chapterFile.exists() && chapterFile.length() > 0L) return chapterFile
    val cacheFile = File(globalCacheAudioDir, name)
    if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile
    return null
  }
}
