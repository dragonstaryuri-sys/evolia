package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.BookEntity
import me.rerere.rikkahub.core.data.db.entity.BookProgressEntity

@Dao
interface BookDAO {
    @Query("SELECT * FROM books ORDER BY last_read_at DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Int): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("SELECT * FROM book_progress WHERE book_id = :bookId")
    suspend fun getProgressByBookId(bookId: Int): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateProgress(progress: BookProgressEntity)

    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    fun getBookWithProgress(bookId: Int): Flow<BookWithProgress?>
}

data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "book_id"
    )
    val progress: BookProgressEntity?
)
