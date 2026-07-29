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
        ),
        ForeignKey(
            entity = DiaryCommentEntity::class,
            parentColumns = ["id"],
            childColumns = ["reply_to_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["diary_id"]), Index(value = ["reply_to_id"])]
)
data class DiaryCommentEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "diary_id")
    val diaryId: String,
    @ColumnInfo(name = "sender_id")
    val senderId: String, // "USER" or Assistant ID
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String? = null, // 回复的目标评论 ID，null 表示直接评论日记而非回复他人
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
