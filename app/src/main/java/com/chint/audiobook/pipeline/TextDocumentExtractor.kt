package com.chint.audiobook.pipeline

import java.io.File
import java.util.Locale

/**
 * Universal document extractor for Plaintext (.txt) and Markdown (.md / .markdown) files,
 * as well as direct user-pasted text and shared web articles.
 *
 * Produces an [ExtractedBook] ready for ingestion through [ImportPipeline].
 *
 * Capabilities:
 *  1. Metadata extraction: YAML frontmatter (title, author, language) or file-name heuristics.
 *  2. Markdown speech cleaning: Strips links, image tags, code fences, blockquote markers,
 *     and markdown emphasis so TTS doesn't articulate raw markup symbols.
 *  3. Smart chapter splitting:
 *     - Headings-based (# Chapter, ## Section, Chapter 1, Part I, PROLOGUE, etc.)
 *     - Natural paragraph-boundary chunking (~2,500 words per chapter) for long un-sectioned text.
 *     - Single-chapter wrapping for short articles and notes (<= 3,000 words).
 */
object TextDocumentExtractor {

  private const val WORDS_PER_CHUNK = 2500
  private const val MAX_SINGLE_CHAPTER_WORDS = 3000

  // Regex patterns for chapter headers
  private val CHAPTER_HEADER_REGEX = Regex(
    """(?i)^\s*(?:#+\s*)?(?:chapter|part|section|book|act|scene)\s+(?:[0-9]+|[ivxlcdm]+|\w+)(?:[:.\s-].*)?$"""
  )
  private val MARKDOWN_HEADING_REGEX = Regex("""^(?:#{1,3})\s+(.+)""")
  private val ALL_CAPS_HEADING_REGEX = Regex("""^[A-Z0-9\s,:'\-]{3,40}$""")

  fun extractFile(file: File): ExtractedBook {
    require(file.exists() && file.isFile) { "File does not exist or is not a file: ${file.absolutePath}" }
    val content = file.readText(Charsets.UTF_8)
    require(content.isNotBlank()) { "Document is empty: ${file.name}" }

    val isMarkdown = file.extension.lowercase(Locale.ROOT) in listOf("md", "markdown")
    val defaultTitle = formatTitleFromFileName(file.nameWithoutExtension)

    return extract(
      rawContent = content,
      defaultTitle = defaultTitle,
      isMarkdown = isMarkdown
    )
  }

  fun extractText(
    title: String,
    rawContent: String,
    author: String = "Imported Document",
    language: String? = null
  ): ExtractedBook {
    require(rawContent.isNotBlank()) { "Content cannot be blank" }
    val isMarkdown = rawContent.contains("# ") || rawContent.contains("```") || rawContent.startsWith("---")
    return extract(
      rawContent = rawContent,
      defaultTitle = title.ifBlank { "Imported Document" },
      defaultAuthor = author,
      isMarkdown = isMarkdown,
      explicitLanguage = language
    )
  }

  private fun extract(
    rawContent: String,
    defaultTitle: String,
    defaultAuthor: String = "Unknown Author",
    isMarkdown: Boolean = false,
    explicitLanguage: String? = null
  ): ExtractedBook {
    var content = rawContent.replace("\r\n", "\n").replace('\r', '\n')
    var title = defaultTitle
    var author = defaultAuthor
    var language = explicitLanguage

    // 1. YAML frontmatter parsing for markdown
    if (content.startsWith("---\n")) {
      val endOfFrontmatter = content.indexOf("\n---\n", startIndex = 4)
      if (endOfFrontmatter != -1) {
        val frontmatter = content.substring(4, endOfFrontmatter)
        content = content.substring(endOfFrontmatter + 5).trimStart()

        for (line in frontmatter.lines()) {
          val colonIdx = line.indexOf(':')
          if (colonIdx > 0) {
            val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
            val value = line.substring(colonIdx + 1).trim().trim('"', '\'')
            when (key) {
              "title" -> if (value.isNotBlank()) title = value
              "author" -> if (value.isNotBlank()) author = value
              "lang", "language" -> if (value.isNotBlank()) language = value
            }
          }
        }
      }
    }

    // 2. Extract title from leading Markdown # Header if present and defaultTitle was a fallback
    if (isMarkdown && (title == defaultTitle || title == "Imported Document")) {
      val firstHeading = content.lines().firstOrNull { it.startsWith("# ") }
      if (firstHeading != null) {
        val candidateTitle = firstHeading.removePrefix("# ").trim()
        if (candidateTitle.isNotBlank()) {
          title = candidateTitle
        }
      }
    }

    // 3. Clean Markdown syntax into speech-friendly prose if document is Markdown
    val preparedContent = if (isMarkdown) cleanMarkdownForSpeech(content) else content

    // 4. Split into chapters
    val chapters = splitIntoChapters(preparedContent, title)

    return ExtractedBook(
      title = title,
      author = author,
      coverBytes = null,
      chapters = chapters,
      language = language
    )
  }

  /**
   * Identifies chapter divisions by headings, falling back to paragraph-boundary chunking
   * for unstructured continuous prose.
   */
  private fun splitIntoChapters(text: String, bookTitle: String): List<ExtractedChapter> {
    val lines = text.lines()
    val headingIndices = mutableListOf<Pair<Int, String>>() // (lineIndex, headingTitle)

    for ((index, line) in lines.withIndex()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue

      val prevEmpty = index == 0 || lines[index - 1].trim().isEmpty()
      val nextEmpty = index == lines.size - 1 || lines[index + 1].trim().isEmpty()

      val mdMatch = MARKDOWN_HEADING_REGEX.matchEntire(trimmed)
      if (mdMatch != null) {
        val headingText = mdMatch.groupValues[1].trim()
        headingIndices.add(index to headingText)
        continue
      }

      if (CHAPTER_HEADER_REGEX.matches(trimmed)) {
        headingIndices.add(index to trimmed)
        continue
      }

      // Check for standalone ALL CAPS heading (e.g. PROLOGUE, EPILOGUE)
      if (prevEmpty && nextEmpty && ALL_CAPS_HEADING_REGEX.matches(trimmed) && trimmed.any { it.isLetter() }) {
        headingIndices.add(index to trimmed)
        continue
      }
    }

    // If we found at least 2 explicit headings, split by them
    if (headingIndices.size >= 2) {
      val chapters = mutableListOf<ExtractedChapter>()
      var chIndex = 1

      // Prelude before first heading (if any significant text exists)
      val firstHeaderLine = headingIndices.first().first
      if (firstHeaderLine > 0) {
        val preludeText = lines.subList(0, firstHeaderLine).joinToString("\n").trim()
        if (countWords(preludeText) >= 30) {
          chapters.add(
            ExtractedChapter(
              index = chIndex++,
              title = "Introduction",
              rawText = preludeText
            )
          )
        }
      }

      for (i in headingIndices.indices) {
        val startLine = headingIndices[i].first
        val endLine = if (i + 1 < headingIndices.size) headingIndices[i + 1].first else lines.size
        val heading = headingIndices[i].second

        // Exclude the heading line itself from the chapter body if it repeats the heading
        val chapterBodyLines = lines.subList(startLine + 1, endLine)
        val chapterBody = chapterBodyLines.joinToString("\n").trim()

        if (chapterBody.isNotBlank()) {
          chapters.add(
            ExtractedChapter(
              index = chIndex++,
              title = heading,
              rawText = chapterBody
            )
          )
        }
      }

      if (chapters.isNotEmpty()) {
        return chapters
      }
    }

    // Fallback: Check total word count
    val totalWords = countWords(text)
    if (totalWords <= MAX_SINGLE_CHAPTER_WORDS) {
      return listOf(
        ExtractedChapter(
          index = 1,
          title = bookTitle,
          rawText = text.trim()
        )
      )
    }

    // Chunk into ~WORDS_PER_CHUNK on paragraph boundaries (\n\n)
    val paragraphs = text.split(Regex("\n\n+"))
    val chapters = mutableListOf<ExtractedChapter>()
    val currentChunk = StringBuilder()
    var currentChunkWords = 0
    var chunkIndex = 1

    for (p in paragraphs) {
      val pTrimmed = p.trim()
      if (pTrimmed.isEmpty()) continue

      val pWords = countWords(pTrimmed)
      if (currentChunkWords + pWords > WORDS_PER_CHUNK && currentChunkWords > 0) {
        chapters.add(
          ExtractedChapter(
            index = chunkIndex,
            title = "Part $chunkIndex",
            rawText = currentChunk.toString().trim()
          )
        )
        chunkIndex++
        currentChunk.clear()
        currentChunkWords = 0
      }

      if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
      currentChunk.append(pTrimmed)
      currentChunkWords += pWords
    }

    if (currentChunk.isNotBlank()) {
      chapters.add(
        ExtractedChapter(
          index = chunkIndex,
          title = "Part $chunkIndex",
          rawText = currentChunk.toString().trim()
        )
      )
    }

    return if (chapters.isNotEmpty()) chapters else listOf(
      ExtractedChapter(index = 1, title = bookTitle, rawText = text.trim())
    )
  }

  /**
   * Converts Markdown formatting into natural prose for TTS audio playback.
   */
  fun cleanMarkdownForSpeech(markdown: String): String {
    var text = markdown

    // Remove images: ![alt](url) -> alt
    text = text.replace(Regex("""!\[([^\]]*)\]\([^)]+\)"""), "$1")

    // Remove links: [text](url) -> text
    text = text.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")

    // Remove code fences: ```lang ... ``` -> ...
    text = text.replace(Regex("""```[a-zA-Z0-9_-]*\r?\n([\s\S]*?)```"""), "$1")

    // Remove inline code ticks: `code` -> code
    text = text.replace(Regex("""`([^`]+)`"""), "$1")

    // Strip bold and italics: **text**, *text*, __text__, _text_
    text = text.replace(Regex("""\*{1,3}([^*]+)\*{1,3}"""), "$1")
    text = text.replace(Regex("""_{1,3}([^_]+)_{1,3}"""), "$1")

    // Strip blockquote markers
    text = text.replace(Regex("""(?m)^[ \t]*>[ \t]*"""), "")

    // Strip horizontal rules: ---, ***, ___
    text = text.replace(Regex("""(?m)^[ \t]*[-*_]{3,}[ \t]*$"""), "\n\n")

    // Strip list markers: - item, * item, + item, 1. item -> item
    text = text.replace(Regex("""(?m)^[ \t]*[-*+][ \t]+"""), "")
    text = text.replace(Regex("""(?m)^[ \t]*\d+\.[ \t]+"""), "")

    // Strip heading hashes: # Heading -> Heading
    text = text.replace(Regex("""(?m)^#{1,6}\s+"""), "")

    return text
  }

  fun formatTitleFromFileName(rawName: String): String {
    val stripped = rawName.replace(Regex("""\.(txt|md|markdown|epub)$""", RegexOption.IGNORE_CASE), "")
    val clean = stripped
      .replace(Regex("""[._-]+"""), " ")
      .trim()
    if (clean.isBlank()) return "Untitled Document"

    return clean.split(Regex("""\s+""")).joinToString(" ") { word ->
      word.lowercase(Locale.ROOT).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
      }
    }
  }

  private fun countWords(str: String): Int {
    if (str.isBlank()) return 0
    return str.split(Regex("""\s+""")).count { it.isNotBlank() }
  }
}
