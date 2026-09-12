package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "AgentDiaryEntity")
data class AgentDiaryEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "images")
    val images: List<DiaryImage> = emptyList(),
    @ColumnInfo(name = "date")
    val date: String, // yyyy-MM-dd
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 生成本篇日记时取到的"最后一条消息"的 createdAt（epoch ms）。
     * 用作下一篇日记生成时的消息起点锚点（优先级最高），替代基于 date 的推算。
     * 老日记为 null，回退到 date 次日 0 点；无交互日记无消息可取，保持 null 不推进游标。
     */
    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: Long? = null
)
