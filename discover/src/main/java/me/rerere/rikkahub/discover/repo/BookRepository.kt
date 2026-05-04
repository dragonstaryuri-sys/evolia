package me.rerere.rikkahub.discover.repo

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.BookDAO
import me.rerere.rikkahub.core.data.db.dao.BookWithProgress
import me.rerere.rikkahub.core.data.db.entity.BookEntity
import me.rerere.rikkahub.core.data.db.entity.BookProgressEntity

class BookRepository(private val bookDAO: BookDAO) {
    fun getAllBooks(): Flow<List<BookEntity>> = bookDAO.getAllBooksFlow()

    fun getBookWithProgress(bookId: Int): Flow<BookWithProgress?> = bookDAO.getBookWithProgress(bookId)

    suspend fun insertBook(book: BookEntity) = bookDAO.insertBook(book)

    suspend fun updateBook(book: BookEntity) = bookDAO.updateBook(book)

    suspend fun deleteBook(book: BookEntity) = bookDAO.deleteBook(book)

    suspend fun updateProgress(progress: BookProgressEntity) = bookDAO.updateProgress(progress)

    suspend fun getProgress(bookId: Int) = bookDAO.getProgressByBookId(bookId)
}
