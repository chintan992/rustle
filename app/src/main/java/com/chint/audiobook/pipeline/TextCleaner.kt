package com.chint.audiobook.pipeline

import java.text.Normalizer

/**
 * Round 10 (Task 2, B3): port of the desktop cleaner semantics from
 * *tts_pipeline/text_clean.py* (READ-ONLY source of truth, executed here).
 *
 * Input: one chapter's raw text as extracted by [EpubExtractor] — paragraphs
 * separated by blank lines ("\n\n"). Output: the cleaned text, paragraphs joined
 * by "\n\n" (the app pipeline's contract with [TextPrepper]).
 *
 * Pipeline (desktop `_remove_spam` + `clean_chapter` order):
 *  1. watermark markers ("please visit", "lightnovelcave", ...) are removed
 *     marker-level — the marker plus its trailing non-space run; surrounding prose
 *     survives (desktop pattern `\s*<marker>[^\s]*` -> " ");
 *  2. generic URL tokens (http://..., www..., bare .com/.net/.me/.org domains) are
 *     removed token-level; the PARAGRAPH SURVIVES (round-10 N2 fix — an old rule
 *     deleted the entire paragraph that merely mentioned a URL);
 *  3. standalone URL-fragment paragraphs (".me", ".comnov/el/b/in[./]net", ...)
 *     are deleted line-wise (desktop rule: requires a letter, so a legitimate
 *     standalone "..." paragraph is left alone);
 *  4. an ellipsis-led paragraph joins the sentence before it (desktop: "word.\n"
 *     + "...Luckily," -> "word... Luckily,");
 *  5. styled tokens (Unicode homoglyph/decoy runs) are decoded per character —
 *     homoglyph table, NFKD-decomposable letters, desktop punctuation table —
 *     then dropped when the decode names a watermark site, or when the original
 *     token is ≤ 7 chars with a non-ASCII letter nothing here can decode (the
 *     injected decorative tags); a legit straddled word like Cyrillic "сhoked"
 *     decodes fully via the homoglyph map and survives;
 *  6. desktop punctuation/symbol/invisible/foreign-digit/emoji tables (generated
 *     from the desktop module into [TextCleanerTables]) are applied in desktop
 *     order, then NFC normalization and desktop whitespace rules;
 *  7. any remaining non-ASCII character has no pronunciation, so it is dropped —
 *     the desktop's own final rule ("the text must be ASCII by now").
 */
object TextCleaner {

  /**
   * Desktop `SPAM_MARKERS` plus the hyphenated "panda-novel" spelling (the
   * pre-round-10 Kotlin cleaner protected it with an invented pattern; keeping the
   * marker keeps that protection while everything else converges on desktop).
   */
  private val SPAM_MARKERS = listOf(
    "please visit", "lightnovelcave", "freewebnovel", "eaglesnovel",
    "novelusb", "pandanovel", "lightnovelworld", "panda-novel"
  )

  /** Desktop `WATERMARK_SITES`: decoded styled tokens naming a site are removed. */
  private val WATERMARK_SITES = setOf(
    "pandanovel", "panda", "lightnovelcave", "lightnovelworld",
    "freewebnovel", "eaglesnovel", "novelusb", "novel", "nova", "cafe"
  )

  /** Cyrillic homoglyph fences (unchanged; round-6 verified behavior). */
  private val HOMOGLYPH_MAP = mapOf(
    '\u0430' to 'a', // Cyrillic small letter a
    '\u0435' to 'e', // Cyrillic small letter ie
    '\u043E' to 'o', // Cyrillic small letter o
    '\u0440' to 'p', // Cyrillic small letter er
    '\u0441' to 'c', // Cyrillic small letter es
    '\u0443' to 'y', // Cyrillic small letter u
    '\u0445' to 'x', // Cyrillic small letter ha
    '\u0410' to 'A', // Cyrillic capital letter A
    '\u0412' to 'B', // Cyrillic capital letter Ve
    '\u0415' to 'E', // Cyrillic capital letter IE
    '\u041A' to 'K', // Cyrillic capital letter Ka
    '\u041C' to 'M', // Cyrillic capital letter Em
    '\u041D' to 'N', // Cyrillic capital letter En
    '\u041E' to 'O', // Cyrillic capital letter O
    '\u0420' to 'P', // Cyrillic capital letter Er
    '\u0421' to 'C', // Cyrillic capital letter Es
    '\u0422' to 'T', // Cyrillic capital letter Te
    '\u0425' to 'X'  // Cyrillic capital letter Ha
  )

  // Desktop _remove_spam: markers first, then fragment paragraphs, then the
  // ellipsis-led paragraph join. The URL-token rule is the round-10 N2 app fix,
  // applied with the same "replace with a space, keep the paragraph" semantics.
  private val markerPatterns: List<Regex> = SPAM_MARKERS.map { marker ->
    Regex("\\s*" + Regex.escape(marker) + "[^\\s]*", RegexOption.IGNORE_CASE)
  }

  /** N2: URL tokens die token-level; paragraphs mentioned in prose survive. */
  private val URL_TOKEN = Regex("https?://\\S+|www\\.\\S+|\\S+\\.(com|net|me|org)\\S*", RegexOption.IGNORE_CASE)

  /**
   * Desktop fragment rule: a whole line containing nothing but a dot-and-letters
   * fragment (".me", ".me<emoji>", ".comnov/el/b/in[./]net"). Requires a letter, so
   * a legitimate standalone "..." paragraph is left alone.
   */
  private val FRAGMENT_PARAGRAPH = Regex("(?m)^[ \\t]*\\.[^\\s]*[a-zA-Z][^\\s]*[ \\t]*$")

  /** Desktop:"...word.\n" + "...Luckily," -> "...word... Luckily,". \n\n added for app blocks. */
  private val ELLIPSIS_JOIN = Regex("([A-Za-z])\\.\\n\\n?(\\.{3,}|…)")

  /** Desktop token loop: word runs (letters, incl. non-ASCII), digits excluded. */
  private val TOKEN = Regex("[\\p{L}]+")

  fun cleanText(rawText: String, language: String = "en"): String {
    val isEnglish = language.isBlank() || language.equals("en", ignoreCase = true)
    var text = rawText

    // 1-4. Spam in desktop order.
    for (pattern in markerPatterns) {
      text = pattern.replace(text, " ")
    }
    text = URL_TOKEN.replace(text, " ")
    text = FRAGMENT_PARAGRAPH.replace(text, "")
    text = ELLIPSIS_JOIN.replace(text, "$1... ")

    // 5. Styled-token decode/drop (only for English fixture scraping where homoglyphs are decoys).
    if (isEnglish) {
      text = TOKEN.replace(text) { m ->
        val token = m.value
        if (token.none { it.code > 126 }) {
          token // pure-ASCII words are never styled (desktop returns them unchanged)
        } else {
          val decoded = buildString { for (c in token) append(decodeChar(c)) }
          val decodedLower = decoded.lowercase()
          when {
            decodedLower in WATERMARK_SITES -> " "
            hasUndecodableLetter(token) && token.length <= 7 -> " "
            else -> decoded
          }
        }
      }
    }

    // 6. Desktop punctuation/invisible/combining/digit/emoji tables, desktop order.
    // Keys are code points (emoji are astral), so replacements go through toChars.
    for ((codePoint, replacement) in TextCleanerTables.PUNCT) {
      text = text.replace(String(Character.toChars(codePoint)), replacement)
    }
    text = Normalizer.normalize(text, Normalizer.Form.NFC)

    // 6b. Desktop whitespace rules.
    text = Regex("[ \\t]+").replace(text, " ")
    text = Regex(" *\\n *").replace(text, "\n")
    text = Regex("\\n{3,}").replace(text, "\n\n")
    text = Regex(" +([,.;:!?])").replace(text, "$1")
    text = text.trim()

    // 7. Desktop final rule: non-ASCII leftovers have no pronunciation in English; drop.
    // For non-English languages, retain all valid Unicode letters, marks, numbers, punctuation, and newlines.
    text = if (isEnglish) {
      String(text.filter { it.code < 128 }.toCharArray())
    } else {
      String(text.filter { c ->
        c == '\n' || (
          !Character.isISOControl(c) &&
            Character.getType(c) != Character.SURROGATE.toInt() &&
            Character.getType(c) != Character.UNASSIGNED.toInt()
        )
      }.toCharArray())
    }

    // App paragraph contract: nonempty lines are paragraphs ("\\n\\n" joins).
    return text.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .joinToString("\n\n")
  }

  /**
   * The letter (or punctuation replacement) a styled character stands for, or ""
   * when everything fails (desktop `_decode_styled_token` per-character fallback:
   * ASCII kept, decoded letter kept, PUNCT_MAP replacement kept, else dropped).
   */
  private fun decodeChar(c: Char): String {
    HOMOGLYPH_MAP[c]?.let { return it.toString() }
    val nfkdLetter = nfkdLetterFor(c)
    if (nfkdLetter != null) return nfkdLetter
    TextCleanerTables.PUNCT[c.code]?.let { return it }
    return ""
  }

  /**
   * NFKD decomposition followed by dropping combining marks: exactly one ASCII
   * letter must remain (desktop `_letter_for` rule). Case is lowercased there too.
   */
  private fun nfkdLetterFor(c: Char): String? {
    val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFKD)
    val stripped = decomposed.filter { !isCombiningMark(it) }
    if (stripped.length != 1) return null
    val single = stripped[0]
    return if (single.code < 128 && single.isLetter()) single.lowercaseChar().toString() else null
  }

  private fun isCombiningMark(c: Char): Boolean {
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() ||
      type == Character.ENCLOSING_MARK.toInt() ||
      type == Character.COMBINING_SPACING_MARK.toInt()
  }

  /** True when the original token still contains a styled letter nothing decodes. */
  private fun hasUndecodableLetter(token: String): Boolean =
    token.any { it.code > 126 && it.isLetter() && HOMOGLYPH_MAP[it] == null && nfkdLetterFor(it) == null }
}
