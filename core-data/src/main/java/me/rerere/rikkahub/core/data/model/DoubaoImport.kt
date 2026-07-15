package me.rerere.rikkahub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DoubaoImportData(
    @SerialName("bot_info") val botInfo: DoubaoBotInfo,
    @SerialName("chat_history") val chatHistory: List<DoubaoHistoryItem>
)

@Serializable
data class DoubaoBotInfo(
    val name: String,
    val avatar: String = "",
    val description: String = ""
)

@Serializable
data class DoubaoHistoryItem(
    @SerialName("user_type") val userType: String,
    @SerialName("create_time") val createTime: String,
    val content: DoubaoContent? = null
)

@Serializable
data class DoubaoContent(
    val text: String? = null,
    @SerialName("tts_content") val ttsContent: String? = null
)
