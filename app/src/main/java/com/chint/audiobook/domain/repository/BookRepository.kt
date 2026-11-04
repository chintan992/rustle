package com.chint.audiobook.domain.repository

import com.chint.audiobook.data.model.Book
import kotlinx.coroutines.flow.StateFlow

interface BookRepository {
  val booksFlow: StateFlow<List<Book>>
  suspend fun scanAndReloadBooks(): List<Book>
  suspend fun deleteBook(slug: String): BookDeleteResult
  fun getBookBySlug(slug: String): Book?
}
