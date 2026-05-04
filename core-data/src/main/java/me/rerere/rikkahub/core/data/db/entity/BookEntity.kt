package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class BookCategory {
    ENTERTAINMENT,
    KNOWLEDGE
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val author: String? = null,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,
    val category: BookCategory,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String? = null, // 绑定的陪伴智能体 ID
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_read_at")
    val lastReadAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "book_progress")
data class BookProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: Int,
    @ColumnInfo(name = "last_position")
    val lastPosition: Int = 0, // 阅读进度，可以是字符偏移或页码
    @ColumnInfo(name = "total_size")
    val totalSize: Int = 0,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int = 0,
    @ColumnInfo(name = "l1_summary")
    val l1Summary: String? = null, // 滚动摘要，用于节省 Token 并维持 AI 记忆
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
