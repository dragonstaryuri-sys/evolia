package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["assistant_id", "is_pinned", "update_at"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @Deprecated("消息数据已迁移至 chat_message_nodes 和 chat_messages 表。请使用 ChatMessageDAO 进行访问。")
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("truncate_index", defaultValue = "-1")
    val truncateIndex: Int,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo(name = "is_consolidated", defaultValue = "0")
    val isConsolidated: Boolean = false,
    @ColumnInfo(name = "enabled_mode_ids", defaultValue = "[]")
    val enabledModeIds: String = "[]",
    @ColumnInfo(name = "context_summary", defaultValue = "")
    val contextSummary: String = "",
    @Deprecated("使用 lastSummarizedMessageTime 代替")
    @ColumnInfo(name = "context_summary_up_to_index", defaultValue = "-1")
    val contextSummaryUpToIndex: Int = -1,
    @ColumnInfo(name = "last_summarized_message_time", defaultValue = "0")
    val lastSummarizedMessageTime: Long = 0L,
    @ColumnInfo(name = "last_prune_time", defaultValue = "0")
    val lastPruneTime: Long = 0L,
    @ColumnInfo(name = "last_prune_message_count", defaultValue = "0")
    val lastPruneMessageCount: Int = 0,
    @ColumnInfo(name = "last_refresh_time", defaultValue = "0")
    val lastRefreshTime: Long = 0L,

    // 重新加回此字段以维持数据库 Schema 稳定性，防止数据被清空
    @Deprecated("VirtualWorld 模式已弃用")
    @ColumnInfo(name = "is_virtual", defaultValue = "0")
    val isVirtual: Boolean = false,
)
