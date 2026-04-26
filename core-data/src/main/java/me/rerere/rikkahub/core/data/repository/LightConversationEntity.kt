package me.rerere.rikkahub.core.data.repository

data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isConsolidated: Boolean,
    val isVirtual: Boolean
)
