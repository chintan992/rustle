package com.chint.audiobook.pipeline

import java.text.Normalizer

/**
 * Round 10 (Task 2, B10): pure port of the desktop chapter-HTML rules from
 * *tts_pipeline/epub_extract.py* (`_to_text`) plus the chapter-title resolution the
 * app pipeline needs.
 *
 * The pre-round-10 app parser never cropped to `<body>`, so `<head><title>` leaked
 * into the chapter text and was read aloud as the "first paragraph" (the fixture's
 * chapter5.html has NO h1 — the old h1 diagnosis tracked the wrong element). The
 * desktop rules fixed by this port:
 *
 *  - crop to `<body>...</body>`;
 *  - strip `<script>`/`<style>` runs and `<!-- comments -->`;
 *  - `<br>` -> line break, block-tag closes -> paragraph break, all other tags out;
 *  - decode entities with the COMPLETE table ([HtmlEntities]) — not the six
 *    hand-picked substitutions of the old parser;
 *  - NFC, CRLF, NBSP cleanup; whitespace-collapsed blocks in paragraph order.
 *
 * Pure JVM-testable string work: no Android framework types.
 */
object ChapterHtmlRules {

  /** Desktop `_DROP_TAGS` never prose (head remnants die in the body crop itself). */
  private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
  private val HTML_COMMENT = Regex("(?s)<!--.*?-->")
  private val BR = Regex("(?i)<br\\s*/?>")
  private val BLOCK_CLOSE = Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr|section|article|pre)\\s*>")
  private val STRAY_TAG = Regex("<[^>]+>")
  private val H1 = Regex("(?is)<h1\\b[^>]*>(.*?)</h1>")
  private val H2 = Regex("(?is)<h2\\b[^>]*>(.*?)</h2>")
  private val H3 = Regex("(?is)<h3\\b[^>]*>(.*?)</h3>")

  /**
   * Desktop `_to_text`: raw chapter XHTML -> whitespace-collapsed paragraph blocks.
   * The body crop is the B10 fix itself (head/title/script/style never reach text).
   */
  fun htmlToBlocks(raw: String): List<String> {
    var body = raw
    val lower = body.lowercase()
    val start = lower.indexOf("<body")
    if (start != -1) {
      val end = lower.indexOf('>', start)
      if (end != -1) {
        body = body.substring(end + 1)
      }
    }
    val bodyEnd = body.lowercase()
      .lastIndexOf("</body>")
    if (bodyEnd != -1) {
      body = body.substring(0, bodyEnd)
    }

    body = SCRIPT_STYLE.replace(body, " ")
    body = HTML_COMMENT.replace(body, " ")
    body = BR.replace(body, "\n")
    body = BLOCK_CLOSE.replace(body, "\n\n")
    body = STRAY_TAG.replace(body, "")

    var text = HtmlEntities.decode(body)
    text = Normalizer.normalize(text, Normalizer.Form.NFC)
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")

    return text.split("\n\n")
      .map { block -> block.split(Regex("\\s+")).joinToString(" ").trim() }
      .filter { it.isNotEmpty() }
  }

  /**
   * Chapter title resolution: TOC (NCX/nav) title is the authority — the desktop's
   * source of truth; when the TOC has none, the app keeps its h1/h2/h3 fallback
   * (first heading text, tags and entities decoded), then "Chapter N".
   */
  fun resolveChapterTitle(tocTitle: String, bodyHtml: String, chapterIndex: Int): String {
    if (tocTitle.isNotBlank()) return collapsed(tocTitle)
    return hHeadingTitle(bodyHtml) ?: "Chapter $chapterIndex"
  }

  private fun hHeadingTitle(bodyHtml: String): String? {
    for (regex in listOf(H1, H2, H3)) {
      regex.find(bodyHtml)?.let { m ->
        val text = collapsed(HtmlEntities.decode(m.groupValues[1].replace(STRAY_TAG, "")))
        if (text.isNotBlank()) return text
      }
    }
    return null
  }

  private fun collapsed(raw: String): String = raw.split(Regex("\\s+")).joinToString(" ").trim()

  /**
   * Desktop rule (round-10 finding: 291 fixture chapters open with a plain `<p>`
   * paragraph that literally repeats the TOC title): when the FIRST block equals
   * the resolved title it is dropped — otherwise the title would be read aloud
   * once as narration and then again as every later re-read.
   */
  fun dropTitleBlock(blocks: List<String>, title: String): List<String> =
    if (title.isNotBlank() && blocks.isNotEmpty() && blocks[0] == title) blocks.drop(1) else blocks
}
