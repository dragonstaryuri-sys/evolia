package me.rerere.rikkahub.core.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val truncateIndex: Int = -1,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val enabledModeIds: Set<Uuid> = emptySet(),
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val isConsolidated: Boolean = false,
    val temporarySummaries: List<String> = emptyList(),
    @Deprecated("Use ChatEpisode instead")
    val contextSummary: String? = null,
    val contextSummaryUpToIndex: Int = -1,
    val lastPruneTime: Long = 0L,
    val lastPruneMessageCount: Int = 0,
    val lastRefreshTime: Long = 0L,
    val isVirtual: Boolean = false,
) {
    val files: List<Uri>
        get() {
            val parts = messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
            val images = parts.filterIsInstance<UIMessagePart.Image>()
                .mapNotNull { it.url.takeIf { it.startsWith("file://") }?.toUri() }
            val documents = parts.filterIsInstance<UIMessagePart.Document>()
                .mapNotNull { it.url.takeIf { it.startsWith("file://") }?.toUri() }
            val videos = parts.filterIsInstance<UIMessagePart.Video>()
                .mapNotNull { it.url.takeIf { it.startsWith("file://") }?.toUri() }
            val audios = parts.filterIsInstance<UIMessagePart.Audio>()
                .mapNotNull { it.url.takeIf { it.startsWith("file://") }?.toUri() }
            return images + documents + videos + audios
        }

    val currentMessages
        get(): List<UIMessage> = messageNodes.map { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
            ?: UIMessage.system("Error: Message missing")
        }

    /**
     * 获取最后一条消息的文本内容
     */
    val lastMessageContent: String
        get() = currentMessages.lastOrNull()?.toContentText() ?: ""

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? =
        messageNodes.firstOrNull { it.messages.contains(message) }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? =
        messageNodes.firstOrNull { it.messages.any { it.id == messageId } }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        val activeVersionTag =
            this.messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }?.currentMessage?.versionTag

        messages.forEach { message ->
            val messageWithTag = if (activeVersionTag != null && message.versionTag == null) {
                message.copy(versionTag = activeVersionTag)
            } else message

            // 1. 尝试通过消息 ID 找到现有的 Node
            val existingNodeIndex = newNodes.indexOfFirst { node ->
                node.messages.any { it.id == messageWithTag.id }
            }

            if (existingNodeIndex != -1) {
                // 2. 如果找到了，精准更新
                val node = newNodes[existingNodeIndex]
                val updatedMessages = node.messages.toMutableList()
                val msgIndex = updatedMessages.indexOfFirst { it.id == messageWithTag.id }
                updatedMessages[msgIndex] = messageWithTag
                newNodes[existingNodeIndex] = node.copy(messages = updatedMessages)
            } else {
                // 3. 只要是新 ID，就添加为新节点（不再限制必须是 ASSISTANT）
                // 这样 Tool Result 和 合并后的 User 消息也能正确占位，不会导致索引偏移
                newNodes.add(messageWithTag.toMessageNode())
            }
        }
        return this.copy(messageNodes = newNodes)
    }

    companion object {
        fun ofId(id: Uuid, assistantId: Uuid, messages: List<MessageNode> = emptyList(), isVirtual: Boolean = false) =
            Conversation(id = id, assistantId = assistantId, messageNodes = messages, isVirtual = isVirtual)

        fun dummy() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
) {
    // 增加安全性保护，防止索引越界崩溃
    val currentMessage
        get() = messages.getOrElse(selectIndex) {
            messages.lastOrNull() ?: UIMessage.system("Error: Node has no messages")
        }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(messages = listOf(message), selectIndex = 0)
    }
}

fun UIMessage.toMessageNode(): MessageNode = MessageNode(messages = listOf(this), selectIndex = 0)
