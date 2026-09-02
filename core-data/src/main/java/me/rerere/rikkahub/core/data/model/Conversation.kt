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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

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
    @Deprecated("Use lastSummarizedMessageTime instead")
    val contextSummaryUpToIndex: Int = -1,
    val lastSummarizedMessageTime: Long = 0L,
    /**
     * 同毫秒复合游标，见 [ConversationEntity.lastSummarizedMessageId] 注释。
     * 空字符串表示"未设置"，SQL 查询退化为仅按时间戳比较，向后兼容老数据。
     */
    val lastSummarizedMessageId: String = "",
    val lastPruneTime: Long = 0L,
    val lastPruneMessageCount: Int = 0,
    val lastRefreshTime: Long = 0L,
    /**
     * L1 segment 自动总结的连续失败次数。每次 summarizeAndRefresh 因 AI 异常失败 +1，成功后重置为 0。
     * 达到 [ChatService.SEGMENT_FAILURE_THRESHOLD] 后停止自动触发，等待用户手动重试或下次成功。
     */
    val segmentFailureCount: Int = 0,
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
        get(): List<UIMessage> = messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
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
                if (msgIndex != -1) {
                    updatedMessages[msgIndex] = messageWithTag
                } else {
                    updatedMessages.add(messageWithTag)
                }
                newNodes[existingNodeIndex] = node.copy(messages = updatedMessages, orderIndex = node.orderIndex)
            } else {
                // 3. 只要是新 ID，就添加为新节点
                newNodes.add(
                    messageWithTag.toMessageNode(this.id).copy(orderIndex = newNodes.size)
                )
            }
        }
        return this.copy(messageNodes = newNodes).normalizeMessageNodes()
    }

    companion object {
        fun ofId(id: Uuid, assistantId: Uuid, messages: List<MessageNode> = emptyList()) =
            Conversation(id = id, assistantId = assistantId, messageNodes = messages)

        fun dummy() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    }
}

/**
 * Keep the in-memory representation identical to the persisted ordering contract.
 * New nodes used to retain the data-class default (0), so a tool-enabled response
 * could contain several nodes with the same order index until the screen was reloaded.
 */
fun Conversation.normalizeMessageNodes(): Conversation {
    val normalizedNodes = messageNodes.mapIndexed { index, node ->
        val normalizedSelectIndex = when {
            node.messages.isEmpty() -> 0
            node.selectIndex in node.messages.indices -> node.selectIndex
            else -> node.messages.lastIndex
        }
        if (
            node.conversationId == id &&
            node.orderIndex == index &&
            node.selectIndex == normalizedSelectIndex
        ) {
            node
        } else {
            node.copy(
                conversationId = id,
                orderIndex = index,
                selectIndex = normalizedSelectIndex
            )
        }
    }
    return if (normalizedNodes == messageNodes) this else copy(messageNodes = normalizedNodes)
}

data class ConversationMessageDeletion(
    val conversation: Conversation,
    val deletedNodeIds: Set<Uuid>,
    val deletedMessageIds: Set<Uuid>
)

/**
 * Delete a set of selected messages in one immutable operation.
 *
 * A generated tool turn can span multiple nodes linked by [UIMessage.versionTag].
 * Removing only one of those nodes leaves an invalid branch, so linked messages from
 * the same assistant turn are removed together. Adjacent tool-call/result messages are
 * also included to preserve the behavior of the single-message delete action.
 */
fun Conversation.deleteMessages(messageIds: Set<Uuid>): ConversationMessageDeletion {
    if (messageIds.isEmpty()) {
        return ConversationMessageDeletion(this, emptySet(), emptySet())
    }

    val idsToDelete = messageIds.toMutableSet()
    val currentNodes = messageNodes.filter { it.messages.isNotEmpty() }
    val currentMessages = currentNodes.map { it.currentMessage }

    messageIds.forEach { selectedId ->
        val selectedNodeIndex = currentNodes.indexOfFirst { node ->
            node.messages.any { it.id == selectedId }
        }
        if (selectedNodeIndex < 0) return@forEach

        val selectedMessage = currentNodes[selectedNodeIndex].messages
            .firstOrNull { it.id == selectedId } ?: return@forEach
        val lastUserIndex = currentNodes
            .take(selectedNodeIndex + 1)
            .indexOfLast { it.role == MessageRole.USER }
        val nextUserOffset = currentNodes
            .drop(selectedNodeIndex + 1)
            .indexOfFirst { it.role == MessageRole.USER }
        val turnEndExclusive = if (nextUserOffset < 0) {
            currentNodes.size
        } else {
            selectedNodeIndex + 1 + nextUserOffset
        }
        val turnStart = (lastUserIndex + 1).coerceAtMost(selectedNodeIndex)

        selectedMessage.versionTag?.let { tag ->
            currentNodes.subList(turnStart, turnEndExclusive).forEach { node ->
                node.messages
                    .filter { it.versionTag == tag }
                    .forEach { idsToDelete += it.id }
            }
        }

        fun hasToolInteraction(message: UIMessage): Boolean = message.parts.any { part ->
            part is UIMessagePart.ToolCall || part is UIMessagePart.ToolResult
        }

        for (index in selectedNodeIndex - 1 downTo 0) {
            val related = currentMessages[index]
            if (!hasToolInteraction(related)) break
            idsToDelete += related.id
        }
        for (index in selectedNodeIndex + 1 until currentMessages.size) {
            val related = currentMessages[index]
            if (!hasToolInteraction(related)) break
            idsToDelete += related.id
        }
    }

    val deletedNodeIds = mutableSetOf<Uuid>()
    val actuallyDeletedMessageIds = mutableSetOf<Uuid>()
    val remainingNodes = messageNodes.mapNotNull { node ->
        // Empty nodes are intentionally retained as placeholders for history that has
        // not been loaded into memory yet. Their absence from idsToDelete is not proof
        // that the persisted node should disappear.
        if (node.messages.isEmpty()) return@mapNotNull node

        val remainingMessages = node.messages.filterNot { message ->
            (message.id in idsToDelete).also { deleted ->
                if (deleted) actuallyDeletedMessageIds += message.id
            }
        }
        if (remainingMessages.isEmpty()) {
            deletedNodeIds += node.id
            null
        } else {
            node.copy(
                messages = remainingMessages,
                selectIndex = node.selectIndex.coerceIn(0, remainingMessages.lastIndex)
            )
        }
    }

    // 删除节点会导致 messageNodes 收缩，truncateIndex 是位置索引，必须同步平移，
    // 否则截断点会错位：归档区(索引 < truncateIndex)的节点被删后，截断点会向后
    // 偏移，把本该保留的最近消息也排除出 AI 上下文；极端情况下 truncateIndex >=
    // 新 size 会导致下次生成上下文为空。这里按"被整段删除且原位于截断点之前"
    // 的节点数量下移 truncateIndex。truncateIndex <= 0 表示未启用截断，保持原值。
    val newTruncateIndex = if (truncateIndex > 0) {
        val deletedNodesBeforeTruncate = messageNodes
            .filterIndexed { index, node ->
                index < truncateIndex && node.id in deletedNodeIds
            }.size
        (truncateIndex - deletedNodesBeforeTruncate)
            .coerceAtLeast(0)
            .coerceAtMost(remainingNodes.size)
    } else {
        truncateIndex
    }

    val updated = copy(
        messageNodes = remainingNodes,
        truncateIndex = newTruncateIndex
    ).normalizeMessageNodes()
    return ConversationMessageDeletion(updated, deletedNodeIds, actuallyDeletedMessageIds)
}

/**
 * Removes only invalid loaded messages while retaining empty nodes used as unloaded
 * history placeholders.
 */
fun Conversation.removeInvalidMessages(): Conversation {
    val loadedNodes = messageNodes
        .filter { node -> node.messages.isNotEmpty() }
        .map { node ->
            if (node.selectIndex in node.messages.indices) node else node.copy(selectIndex = 0)
        }
    val validLoadedNodes = mutableListOf<MessageNode>()

    loadedNodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        val nextNode = loadedNodes.getOrNull(index + 1)
        val hasToolCall = message.parts.any { part -> part is UIMessagePart.ToolCall }
        val nextHasToolResult = nextNode?.currentMessage?.parts?.any { part ->
            part is UIMessagePart.ToolResult
        } == true
        val isBrokenToolCall = hasToolCall && !nextHasToolResult
        val isBlankAssistantAtEnd = index == loadedNodes.lastIndex &&
            message.role == MessageRole.ASSISTANT &&
            message.toContentText().isBlank() &&
            message.parts.none { part -> part is UIMessagePart.ToolCall }
        val isDuplicateAssistant = message.role == MessageRole.ASSISTANT &&
            nextNode?.currentMessage?.role == MessageRole.ASSISTANT

        when {
            isBrokenToolCall -> {
                val fallbackMessages = node.messages.filter { candidate ->
                    candidate.id != message.id
                }
                if (fallbackMessages.isNotEmpty()) {
                    validLoadedNodes += node.copy(
                        messages = fallbackMessages,
                        selectIndex = 0
                    )
                }
            }
            isBlankAssistantAtEnd -> {
                // 多版本场景（重新生成失败后留下空白的新版本）：
                // 只剔除失败的空版本，回退到上一个有效版本，保留历史版本；
                // 仅当节点是单版本占位时才丢弃整条节点。
                val fallbackMessages = node.messages.filter { candidate ->
                    candidate.id != message.id
                }
                if (fallbackMessages.isNotEmpty()) {
                    validLoadedNodes += node.copy(
                        messages = fallbackMessages,
                        selectIndex = fallbackMessages.lastIndex
                    )
                }
            }
            isDuplicateAssistant -> {
                val isVisible = message.toContentText().isNotBlank() ||
                    message.parts.any { part -> part is UIMessagePart.ToolCall }
                if (isVisible) validLoadedNodes += node
            }
            else -> validLoadedNodes += node
        }
    }

    val validLoadedNodesById = validLoadedNodes.associateBy { node -> node.id }
    val reconciledNodes = messageNodes.mapNotNull { node ->
        if (node.messages.isEmpty()) node else validLoadedNodesById[node.id]
    }
    return copy(messageNodes = reconciledNodes).normalizeMessageNodes()
}

/**
 * Restore message graph entries from an undo snapshot without replacing nodes or
 * message versions that were added after the snapshot was captured.
 */
fun Conversation.restoreMessagesFrom(backup: Conversation): Conversation {
    require(id == backup.id) {
        "Cannot restore messages from conversation ${backup.id} into $id"
    }

    val currentNodesById = messageNodes.associateBy { node -> node.id }
    val backupNodeIds = backup.messageNodes.mapTo(mutableSetOf()) { node -> node.id }
    val restoredBackupNodes = backup.messageNodes.map { backupNode ->
        val currentNode = currentNodesById[backupNode.id] ?: return@map backupNode
        val backupMessageIds = backupNode.messages.mapTo(mutableSetOf()) { message -> message.id }
        val newerMessages = currentNode.messages.filter { message -> message.id !in backupMessageIds }
        val mergedMessages = backupNode.messages + newerMessages
        val selectedMessageId = if (newerMessages.isEmpty()) {
            backupNode.messages.getOrNull(backupNode.selectIndex)?.id
        } else {
            currentNode.messages.getOrNull(currentNode.selectIndex)?.id
        }
        val mergedSelectIndex = mergedMessages
            .indexOfFirst { message -> message.id == selectedMessageId }
            .takeIf { index -> index >= 0 }
            ?: mergedMessages.lastIndex.coerceAtLeast(0)

        currentNode.copy(
            messages = mergedMessages,
            selectIndex = mergedSelectIndex,
            timelineCreatedAt = currentNode.timelineCreatedAt
                .takeIf { createdAt -> createdAt > 0L }
                ?: backupNode.timelineCreatedAt
        )
    }
    val newerNodes = messageNodes.filter { node -> node.id !in backupNodeIds }
    return copy(messageNodes = restoredBackupNodes + newerNodes).normalizeMessageNodes()
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    val conversationId: Uuid,
    // 会话内排序保留给持久化同步。
    val orderIndex: Int = 0,
    // 全局时间线分页游标。它取节点最早消息时间，创建后不再随会话更新而改变。
    val timelineCreatedAt: Long = 0L
) {
    val currentMessage
        get() = messages.getOrElse(selectIndex) {
            messages.lastOrNull() ?: UIMessage.system("Error: Node has no messages")
        }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage, conversationId: Uuid) = MessageNode(
            messages = listOf(message),
            conversationId = conversationId,
            timelineCreatedAt = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        )
    }
}

/**
 * 将单条 UIMessage 转换为 MessageNode。
 * 自动从消息的 createdAt 提取时间戳作为节点的 timelineCreatedAt 游标。
 */
fun UIMessage.toMessageNode(conversationId: Uuid): MessageNode = MessageNode(
    messages = listOf(this),
    conversationId = conversationId,
    timelineCreatedAt = this.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
)
