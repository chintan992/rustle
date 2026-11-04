package com.chint.audiobook.pipeline

import com.chint.audiobook.core.TextSegmenter
import com.chint.audiobook.data.model.Segment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class LexiconRule(
  val term: String,
  val replacement: String,
  val ignoreCase: Boolean = true
)

object TextPrepper {

  // Measured 20 safe markup tags from SUPERTONIC.md §4.3
  private val ALLOWED_TAGS = setOf(
    "breath", "burp", "calm", "clears_throat", "cough", "cry", "groan",
    "heavy_breath", "hiccup", "laugh", "laughter", "moan", "sad", "sigh",
    "yawn", "nod", "slow", "laughs", "coughs", "laughs_softly"
  )

  // Expressive narration (auto-insertion). Conservative rules only — every
  // replacement lands on a measured-safe tag from SUPERTONIC.md §4.3; the
  // §4.3 "ambiguous" set (<pause>, <fast>, ...) is deliberately never inserted.
  // Laugh-syllable runs: "hahaha", "ha ha ha!", "ha-ha-ha". "he"/"ho" runs need
  // 3+ repetitions because a two-word "he he"/"ho ho" can be legitimate prose.
  // Intra-run separators are whitespace/comma/dashes ONLY: runs may not span
  // sentence punctuation ("Hahaha! Ha ha ha" is two cues, not one), which both
  // keeps per-sentence cadence and stops paragraph-crossing greed.
  private val LAUGH_SEP = """[\s,\u2013\u2014-]*"""
  private val LAUGH_RUN = Regex("""\bha(?:${LAUGH_SEP}ha)+\b""", RegexOption.IGNORE_CASE)
  private val HEE_RUN = Regex("""\bhe(?:${LAUGH_SEP}he){2,}\b""", RegexOption.IGNORE_CASE)
  private val HO_RUN = Regex("""\bho(?:${LAUGH_SEP}ho){2,}\b""", RegexOption.IGNORE_CASE)

  // Parenthesised stage directions are directions, not prose — unambiguous.
  private val STAGE_LAUGH = Regex("""\(\s*(?:laughs?|laughing|giggles?|giggling)\s*\)""", RegexOption.IGNORE_CASE)
  private val STAGE_SIGH = Regex("""\(\s*(?:sighs?|sighing)\s*\)""", RegexOption.IGNORE_CASE)
  private val STAGE_COUGH = Regex("""\(\s*(?:coughs?|coughing)\s*\)""", RegexOption.IGNORE_CASE)
  private val STAGE_THROAT = Regex("""\(\s*clears? throat\s*\)""", RegexOption.IGNORE_CASE)

  private val json = Json { prettyPrint = true; encodeDefaults = true }

  fun prepareChapter(
    chapterIndex: Int,
    cleanedText: String,
    voice: String,
    outputDir: File,
    modelDir: File? = null,
    lexiconRules: List<LexiconRule> = emptyList(),
    expressive: Boolean = true,
    language: String = "en"
  ): Pair<String, List<Segment>> {
    // 1. Apply lexicon replacement
    var text = applyLexicon(cleanedText, lexiconRules)

    // 2. Pre-process expression tags (e.g. *** -> <breath>)
    text = text.replace("***", "<breath>")

    // 2b. Expressive narration: auto-insert measured-safe cues (English only —
    // the tag set was measured against English delivery; see SUPERTONIC.md §4).
    val isEnglish = language.isBlank() || language.equals("en", ignoreCase = true)
    if (expressive && isEnglish) {
      text = insertExpressionCues(text)
    }

    // 3. Filter expression tags: keep allowed tags, remove banned/unsupported tags
    text = filterTags(text)

    // 4. Compute dynamic model digest and segment prepared text
    // modelDir must point to the actual model pack (tts.json + ONNX files), not the book output dir.
    val modelDigest = modelDir?.let { com.chint.audiobook.core.SegmentHasher.computeModelDigest(it) }
      ?: com.chint.audiobook.core.SegmentHasher.DEFAULT_MODEL_DIGEST
    val segments = TextSegmenter.segmentText(text, voice, modelDigest = modelDigest, language = language)

    // 5. Write prepared text file (chNNNN.prepared.txt)
    val chapterSlug = "ch${"%04d".format(chapterIndex)}"
    val preparedFile = File(outputDir, "chapters/$chapterSlug.prepared.txt")
    preparedFile.parentFile?.mkdirs()
    preparedFile.writeText(text)

    // 6. Write segments file (chNNNN.segments.json)
    val segmentsFile = File(outputDir, "chapters/$chapterSlug.segments.json")
    segmentsFile.writeText(json.encodeToString(segments))

    return Pair("chapters/$chapterSlug.prepared.txt", segments)
  }

  private fun applyLexicon(text: String, rules: List<LexiconRule>): String {
    var result = text
    for (rule in rules) {
      val option = if (rule.ignoreCase) RegexOption.IGNORE_CASE else RegexOption.LITERAL
      val regex = Regex(Regex.escape(rule.term), option)
      result = regex.replace(result, rule.replacement)
    }
    return result
  }

  /**
   * Expressive narration pass: laugh-syllable runs (which the model would read
   * as garbled repeated syllables) become <laughter>, and parenthesised stage
   * directions become their vocalisation tags. All targets are in ALLOWED_TAGS,
   * so the inserted cues survive [filterTags] unchanged.
   */
  private fun insertExpressionCues(text: String): String {
    var result = text
    result = LAUGH_RUN.replace(result, "<laughter>")
    result = HEE_RUN.replace(result, "<laughter>")
    result = HO_RUN.replace(result, "<laughter>")
    result = STAGE_LAUGH.replace(result, "<laugh>")
    result = STAGE_SIGH.replace(result, "<sigh>")
    result = STAGE_COUGH.replace(result, "<cough>")
    result = STAGE_THROAT.replace(result, "<clears_throat>")
    return result
  }

  private fun filterTags(text: String): String {
    val tagRegex = Regex("<([a-zA-Z0-9_]+)>")
    return tagRegex.replace(text) { matchResult ->
      val tagName = matchResult.groupValues[1].lowercase()
      if (ALLOWED_TAGS.contains(tagName)) {
        "<${tagName}>"
      } else {
        ""
      }
    }
  }
}
