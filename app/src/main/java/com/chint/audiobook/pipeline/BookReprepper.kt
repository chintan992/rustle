package com.chint.audiobook.pipeline

import android.content.Context
import com.chint.audiobook.core.LanguageCatalog
import com.chint.audiobook.core.LanguageDetector
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.ChapterRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Beta round WS2: re-prep machinery — the inverse of the import flow for one
 * already-imported book. Re-runs clean + prepare per chapter FROM THE PERSISTED
 * RAW TEXT (chapters/chNNNN.txt), rewrites the prepared.txt/segments.json pair
 * and book.json, all offline. Callers:
 *  - the VoiceSheet language override (choice = "auto"|code),
 *  - the WS1 expressive-narration toggle (all books, stored language kept).
 *
 * Segment hashes include lang (and voice), so every re-prep produces fresh
 * hashes: old WAVs simply age out per the existing orphan/cap cleanup and a
 * re-prepped book re-renders from scratch. Same contract as ImportPipeline —
 * atomic temp-dir rename is NOT needed here because every rewritten file is
 * written in one shot (writeText) and a crash just leaves the next re-prep to
 * fix it.
 */
class BookReprepper(
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

  private val json = Json { prettyPrint = true; encodeDefaults = true }
  private val readJson = Json { ignoreUnknownKeys = true }

  /**
   * Re-prep one book. [languageChoice]: LanguageCatalog.AUTO (re-detect from
   * the raw chapters) or a catalog code. [expressive]/[voice]: the current
   * app settings (they feed the expressive gate and the segment hashes).
   *
   * @return the EFFECTIVE language the book now carries (auto resolves
   * through detection with the previous language as fallback), or null when
   * the book dir/manifest is gone.
   */
  suspend fun reprepBook(
    slug: String,
    languageChoice: String,
    expressive: Boolean,
    voice: String
  ): String? = withContext(ioDispatcher) {
    val bookDir = File(context.filesDir, "books/$slug")
    val bookJsonFile = File(bookDir, "book.json")
    val book = if (bookJsonFile.exists()) {
      runCatching { readJson.decodeFromString<Book>(bookJsonFile.readText()) }.getOrNull()
    } else null
    if (book == null || bookDir.isDirectory.not()) return@withContext null

    val effective = resolveEffective(bookDir, book, languageChoice)

    val modelDir = File(context.filesDir, "models/supertonic-3-int8")
    val updatedChapters = mutableListOf<ChapterRef>()
    for (chapter in book.chapters) {
      val rawFile = File(bookDir, "chapters/ch${"%04d".format(chapter.index)}.txt")
      if (!rawFile.exists()) {
        // No raw source -> keep the chapter's current prep untouched.
        updatedChapters.add(chapter)
        continue
      }
      val raw = rawFile.readText()
      val cleaned = TextCleaner.cleanText(raw, language = effective)
      val (preparedRelPath, _) = TextPrepper.prepareChapter(
        chapterIndex = chapter.index,
        cleanedText = cleaned,
        voice = voice,
        outputDir = bookDir,
        modelDir = modelDir,
        expressive = expressive,
        language = effective
      )
      updatedChapters.add(chapter.copy(prepared = preparedRelPath))
    }

    val updated = book.copy(
      language = effective,
      chapters = updatedChapters
    )
    bookJsonFile.writeText(json.encodeToString(updated))
    effective
  }

  /**
   * WS1 expressive toggle: re-prep EVERY book with the new expressive flag,
   * keeping each book's stored language (choice passes the book's own code, so
   * no re-detection). Continuous — a failure on one book does not stop the rest.
   *
   * @return count of books successfully re-prepped.
   */
  suspend fun reprepAllBooks(expressive: Boolean, voice: String): Int = withContext(ioDispatcher) {
    val booksDir = File(context.filesDir, "books")
    if (!booksDir.exists()) return@withContext 0
    var done = 0
    for (subDir in booksDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
      val bookJsonFile = File(subDir, "book.json")
      val book = runCatching { readJson.decodeFromString<Book>(bookJsonFile.readText()) }.getOrNull()
        ?: continue
      val ok = reprepBook(book.slug, book.language, expressive, voice) != null
      if (ok) done++
    }
    done
  }

  /**
   * Effective language for a re-prep: an explicit catalog code wins; "auto"
   * samples the first chapters' raw text (concatenated, ~8 KB — plenty for the
   * detector) and falls back to the book's stored language (then the default).
   */
  private fun resolveEffective(bookDir: File, book: Book, languageChoice: String): String {
    if (languageChoice != LanguageCatalog.AUTO) {
      return LanguageCatalog.normalizeOrNull(languageChoice) ?: book.getLanguageSafe()
    }
    val sample = buildString {
      for (chapter in book.chapters) {
        if (length >= 8_000) break
        val rawFile = File(bookDir, "chapters/ch${"%04d".format(chapter.index)}.txt")
        if (!rawFile.exists()) continue
        if (isNotEmpty()) append("\n\n")
        append(rawFile.readText())
      }
    }
    return LanguageDetector.detect(sample) ?: book.getLanguageSafe()
  }

  private fun Book.getLanguageSafe(): String =
    LanguageCatalog.normalizeOrNull(language) ?: LanguageCatalog.DEFAULT
}
