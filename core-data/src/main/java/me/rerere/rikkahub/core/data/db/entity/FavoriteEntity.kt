package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 收藏类型: 0 - 单条消息, 1 - 合并消息
    @ColumnInfo(name = "type")
    val type: Int,

    // 收藏的消息内容 (JSON 序列化存储)
    @ColumnInfo(name = "content")
    val content: String,

    // 发送者名称 (单条时使用)
    @ColumnInfo(name = "sender_name")
    val senderName: String = "",

    // 智能体名称 (合并时显示在卡片底部，单条时如果是智能体发的也存这)
    @ColumnInfo(name = "agent_name")
    val agentName: String = "",

    // 用户昵称 (合并时显示在标题)
    @ColumnInfo(name = "user_nickname")
    val userNickname: String = "",

    // 消息时间 (单条消息的时间，或多条中第一条的时间)
    @ColumnInfo(name = "message_time")
    val messageTime: Long,

    // 收藏操作的时间
    @ColumnInfo(name = "create_at")
    val createAt: Long = System.currentTimeMillis()
)
