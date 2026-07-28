package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(
    tableName = "DiaryCommentEntity",
    foreignKeys = [
        ForeignKey(
            entity = AgentDiaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["diary_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["diary_id"])]
)
data class DiaryCommentEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "diary_id")
    val diaryId: String,
    @ColumnInfo(name = "sender_id")
    val senderId: String, // "USER" or Assistant ID
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
