package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.dao.BookWithProgress
import me.rerere.rikkahub.core.data.db.entity.BookCategory
import me.rerere.rikkahub.core.data.db.entity.BookEntity
import me.rerere.rikkahub.core.data.db.entity.BookProgressEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.discover.repo.BookRepository

class BookViewModel(
    private val bookRepository: BookRepository,
    val assistants: StateFlow<List<Assistant>>
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = bookRepository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getBookWithProgress(bookId: Int): Flow<BookWithProgress?> {
        return bookRepository.getBookWithProgress(bookId)
    }

    fun uploadBook(
        title: String,
        author: String?,
        filePath: String,
        category: BookCategory,
        assistantId: String?
    ) {
        viewModelScope.launch {
            val book = BookEntity(
                title = title,
                author = author,
                filePath = filePath,
                category = category,
                assistantId = assistantId
            )
            val id = bookRepository.insertBook(book)
            // 初始化进度
            bookRepository.updateProgress(BookProgressEntity(bookId = id.toInt()))
        }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
        }
    }

    fun updateReadingProgress(bookId: Int, position: Int, summary: String? = null) {
        viewModelScope.launch {
            val current = bookRepository.getProgress(bookId) ?: BookProgressEntity(bookId = bookId)
            bookRepository.updateProgress(current.copy(
                lastPosition = position,
                l1Summary = summary ?: current.l1Summary,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }
}
