package com.chint.audiobook.data.repository

import android.content.Context
import com.chint.audiobook.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.chint.audiobook.domain.repository.BookDeleteResult

class LibraryRepository @Inject constructor(
  @ApplicationContext private val context: Context
) : com.chint.audiobook.domain.repository.BookRepository {

  private val json = Json { ignoreUnknownKeys = true }
  private val _booksFlow = MutableStateFlow<List<Book>>(emptyList())
  override val booksFlow: StateFlow<List<Book>> = _booksFlow.asStateFlow()

  override suspend fun scanAndReloadBooks(): List<Book> = withContext(Dispatchers.IO) {
    val booksDir = File(context.filesDir, "books")
    if (!booksDir.exists()) {
      _booksFlow.value = emptyList()
      return@withContext emptyList()
    }

    val loadedBooks = mutableListOf<Book>()
    booksDir.listFiles()?.forEach { subDir ->
      if (subDir.isDirectory) {
        val bookJsonFile = File(subDir, "book.json")
        if (bookJsonFile.exists() && bookJsonFile.length() > 0L) {
          runCatching {
            json.decodeFromString<Book>(bookJsonFile.readText())
          }.getOrNull()?.let { book ->
            loadedBooks.add(book)
          }
        }
      }
    }

    _booksFlow.value = loadedBooks
    loadedBooks
  }

  /** BG0: outcome of a delete attempt — the manage sheet reports [PARTIAL]. */
  /**
   * BG0 (ghost-book fix): ALWAYS rescans after the attempt. The old code only
   * rescanned `if (deleted)` — a partial delete (files pinned by a live
   * player, both onDelete race) skipped the rescan entirely, freezing the
   * stale entry in the shelf/Continue card while half its files were gone.
   * PARTIAL means the directory still exists; the caller can surface it.
   */
  override suspend fun deleteBook(slug: String): BookDeleteResult = withContext(Dispatchers.IO) {
    val bookDir = File(context.filesDir, "books/$slug")
    when {
      !bookDir.exists() -> {
        scanAndReloadBooks()
        BookDeleteResult.MISSING
      }
      bookDir.deleteRecursively() -> {
        scanAndReloadBooks()
        BookDeleteResult.DELETED
      }
      else -> {
        scanAndReloadBooks()
        BookDeleteResult.PARTIAL
      }
    }
  }

  override fun getBookBySlug(slug: String): Book? {
    return _booksFlow.value.find { it.slug == slug }
  }
}
