package com.chint.audiobook.pipeline

import android.content.Context
import com.chint.audiobook.core.LanguageCatalog
import com.chint.audiobook.core.LanguageDetector
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.ChapterRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

sealed class ImportProgress {
  data object Parsing : ImportProgress()
  data class Cleaning(val done: Int, val total: Int) : ImportProgress()
  data class Preparing(val done: Int, val total: Int) : ImportProgress()
  data class Completed(val bookSlug: String) : ImportProgress()
  data class Failed(val error: String) : ImportProgress()
}

class ImportPipeline(private val context: Context) {

  private val json = Json { prettyPrint = true; encodeDefaults = true }

  suspend fun importBook(
    extractedBook: ExtractedBook,
    defaultVoice: String = "F2",
    expressive: Boolean = true,
    onProgress: (ImportProgress) -> Unit = {}
  ): Result<Book> = withContext(Dispatchers.IO) {
    runCatching {
      val baseBooksDir = File(context.filesDir, "books")
      val baseSlug = generateSlug(extractedBook.title, extractedBook.author)
      val slug = resolveUniqueSlug(baseBooksDir, baseSlug)

      // Language detection: sample from early chapters, fallback to extractedBook.language, then default
      val sampleText = buildString {
        for (ch in extractedBook.chapters) {
          if (length >= 8_000) break
          if (isNotEmpty()) append("\n\n")
          append(ch.rawText)
        }
      }
      val detected = LanguageDetector.detect(sampleText)
      val metaLang = LanguageCatalog.normalizeOrNull(extractedBook.language)
      val effectiveLanguage = detected ?: metaLang ?: LanguageCatalog.DEFAULT

      val tempDir = File(baseBooksDir, "$slug.tmp_${System.currentTimeMillis()}")
      val finalDir = File(baseBooksDir, slug)

      if (tempDir.exists()) tempDir.deleteRecursively()
      tempDir.mkdirs()

      // Save cover image if present
      var coverPath: String? = null
      extractedBook.coverBytes?.let { bytes ->
        val coverFile = File(tempDir, "cover.jpg")
        coverFile.writeBytes(bytes)
        coverPath = "cover.jpg"
      }

      val chaptersCount = extractedBook.chapters.size
      val chapterRefs = mutableListOf<ChapterRef>()

      for ((index, extractedCh) in extractedBook.chapters.withIndex()) {
        onProgress(ImportProgress.Cleaning(index + 1, chaptersCount))
        val cleaned = TextCleaner.cleanText(extractedCh.rawText, language = effectiveLanguage)

        // Save raw chapter text
        val rawChapterFile = File(tempDir, "chapters/ch${"%04d".format(extractedCh.index)}.txt")
        rawChapterFile.parentFile?.mkdirs()
        rawChapterFile.writeText(extractedCh.rawText)

        onProgress(ImportProgress.Preparing(index + 1, chaptersCount))
        // modelDir points at the real model pack (tts.json + ONNX files) so the
        // segment digest matches the engine that will render the audio.
        val modelDir = File(context.filesDir, "models/supertonic-3-int8")
        val (preparedRelPath, _) = TextPrepper.prepareChapter(
          chapterIndex = extractedCh.index,
          cleanedText = cleaned,
          voice = defaultVoice,
          outputDir = tempDir,
          modelDir = modelDir,
          expressive = expressive,
          language = effectiveLanguage
        )

        val wordCount = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }
        chapterRefs.add(
          ChapterRef(
            index = extractedCh.index,
            title = extractedCh.title,
            prepared = preparedRelPath,
            wordCount = wordCount
          )
        )
      }

      val book = Book(
        slug = slug,
        title = extractedBook.title,
        author = extractedBook.author,
        language = effectiveLanguage,
        coverPath = coverPath,
        chapters = chapterRefs
      )

      val bookJsonFile = File(tempDir, "book.json")
      bookJsonFile.writeText(json.encodeToString(book))

      // Atomic move: rename tempDir -> finalDir with copy fallback
      if (finalDir.exists()) finalDir.deleteRecursively()
      val renamed = tempDir.renameTo(finalDir)
      if (!renamed) {
        tempDir.copyRecursively(finalDir, overwrite = true)
        tempDir.deleteRecursively()
      }

      onProgress(ImportProgress.Completed(slug))
      book
    }
  }

  suspend fun importEpub(
    epubFile: File,
    defaultVoice: String = "F2",
    expressive: Boolean = true,
    onProgress: (ImportProgress) -> Unit = {}
  ): Result<Book> {
    onProgress(ImportProgress.Parsing)
    val extracted = runCatching { EpubExtractor.extractEpub(epubFile) }
    return extracted.fold(
      onSuccess = { importBook(it, defaultVoice, expressive, onProgress) },
      onFailure = { Result.failure(it) }
    )
  }

  suspend fun importFile(
    file: File,
    defaultVoice: String = "F2",
    expressive: Boolean = true,
    onProgress: (ImportProgress) -> Unit = {}
  ): Result<Book> {
    onProgress(ImportProgress.Parsing)
    val ext = file.extension.lowercase(Locale.ROOT)
    val extracted = runCatching {
      when (ext) {
        "txt", "md", "markdown" -> TextDocumentExtractor.extractFile(file)
        "epub" -> {
          try {
            EpubExtractor.extractEpub(file)
          } catch (_: Exception) {
            TextDocumentExtractor.extractFile(file)
          }
        }
        else -> {
          try {
            EpubExtractor.extractEpub(file)
          } catch (_: Exception) {
            TextDocumentExtractor.extractFile(file)
          }
        }
      }
    }
    return extracted.fold(
      onSuccess = { importBook(it, defaultVoice, expressive, onProgress) },
      onFailure = { Result.failure(it) }
    )
  }

  suspend fun importText(
    title: String,
    content: String,
    author: String = "Imported Document",
    defaultVoice: String = "F2",
    expressive: Boolean = true,
    onProgress: (ImportProgress) -> Unit = {}
  ): Result<Book> {
    onProgress(ImportProgress.Parsing)
    val extracted = runCatching {
      TextDocumentExtractor.extractText(title = title, rawContent = content, author = author)
    }
    return extracted.fold(
      onSuccess = { importBook(it, defaultVoice, expressive, onProgress) },
      onFailure = { Result.failure(it) }
    )
  }

  companion object {
    fun generateSlug(title: String, author: String): String {
      val raw = "$title $author".lowercase()
      val slugified = raw.replace(Regex("[^a-z0-9\\p{L}]+"), "_").trim('_')
      return if (slugified.isBlank()) "book_${System.currentTimeMillis()}" else slugified
    }

    fun resolveUniqueSlug(baseDir: File, baseSlug: String): String {
      if (!File(baseDir, baseSlug).exists()) return baseSlug
      var counter = 2
      while (File(baseDir, "${baseSlug}_$counter").exists()) {
        counter++
      }
      return "${baseSlug}_$counter"
    }
  }
}
