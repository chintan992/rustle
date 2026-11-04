package com.chint.audiobook.domain.usecase

import com.chint.audiobook.data.model.Book
import com.chint.audiobook.domain.repository.BookDeleteResult
import com.chint.audiobook.domain.repository.BookRepository
import kotlinx.coroutines.flow.StateFlow

class GetLibraryBooksUseCase(private val bookRepository: BookRepository) {
  operator fun invoke(): StateFlow<List<Book>> = bookRepository.booksFlow
}

class ReloadLibraryUseCase(private val bookRepository: BookRepository) {
  suspend operator fun invoke(): List<Book> = bookRepository.scanAndReloadBooks()
}

class DeleteBookUseCase(private val bookRepository: BookRepository) {
  suspend operator fun invoke(slug: String): BookDeleteResult = bookRepository.deleteBook(slug)
}

class GetBookBySlugUseCase(private val bookRepository: BookRepository) {
  operator fun invoke(slug: String): Book? = bookRepository.getBookBySlug(slug)
}
