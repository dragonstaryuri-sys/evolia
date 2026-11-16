package me.rerere.rikkahub.service

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.db.entity.ChatUnreadMessageEntity
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 未读消息角标的纯决策函数（不依赖 Android API，单测固化）。
 *
 * 核心语义：
 * - 未读以「消息节点」为单位（一个节点至多一条未读记录，多版本消息天然去重）；
 * - 只有用户实际可见的 ASSISTANT 消息计入未读，可见性判定与 ChatList 的渲染过滤
 *   （groupIntoTurns 的 !currentMessage.skipContext）保持同一口径；
 * - 未读候选 = 生成结束时「可见 ASSISTANT 节点」 − 「生成开始时已可见的节点基线」
 *   − 「已按句处理过的节点（微信模式逐句决策）」。
 *   基线用「生成开始时已有可见内容的节点集合」而非「全部节点 id 集合」，
 *   因为 AI 回复的首条消息会复用生成前已存在的空占位节点 id（processMessageIds）。
 */
internal object UnreadRules {

    /**
     * 判定消息节点的当前选中版本是否为用户可见的 ASSISTANT 消息。
     *
     * 排除：
     * - 空占位/未水合节点（messages 为空）；
     * - USER / TOOL / SYSTEM 角色；
     * - skipContext 隐藏消息（通话转写 CALL、通话状态 CALL_STATE、拨号决策 CALL_REQUEST、
     *   自动化指令 AGENT_TASK、日记评论 DIARY_COMMENT 等全部带 skipContext=true）；
     * - 无可见内容的节点（纯工具调用 / 纯思考过程，文本空白且无音频/图片）。
     */
    fun isVisibleAssistantNode(node: MessageNode): Boolean {
        if (node.messages.isEmpty()) return false
        val message = node.currentMessage
        if (message.role != MessageRole.ASSISTANT) return false
        if (message.skipContext) return false
        val hasText = message.toContentText().isNotBlank()
        val hasMedia = message.parts.any { part ->
            part is UIMessagePart.Audio || part is UIMessagePart.Image
        }
        return hasText || hasMedia
    }

    /**
     * 从生成结束后的会话快照中挑出本轮未读候选节点，并转换为未读记录实体。
     *
     * @param conversation 生成结束后的会话快照
     * @param baselineVisibleNodeIds 生成开始时「已有可见内容」的节点 id 集合
     *   （handleMessageComplete 进入即快照；刷新回复是在已有内容节点上追加版本，
     *   节点已在基线内，不会重复计数）
     * @param excludeNodeIds 已处理过未读决策的节点 id（微信模式逐句路径已决策，
     *   防止 onSuccess 汇总时重复插入或覆盖用户中途查看的已读状态）
     */
    fun pickUnreadCandidates(
        conversation: Conversation,
        baselineVisibleNodeIds: Set<Uuid>,
        excludeNodeIds: Set<Uuid> = emptySet()
    ): List<ChatUnreadMessageEntity> {
        if (baselineVisibleNodeIds.isEmpty() && conversation.messageNodes.isEmpty()) return emptyList()
        return conversation.messageNodes
            .filter { node ->
                val isNewVisibleNode = node.id !in baselineVisibleNodeIds
                val isNotExcluded = node.id !in excludeNodeIds
                isNewVisibleNode && isNotExcluded && isVisibleAssistantNode(node)
            }
            .map { node -> toUnreadEntity(node, conversation.id) }
    }

    /**
     * 可见 ASSISTANT 节点 → 未读记录实体。
     * createdAt 取节点时间线游标（timelineCreatedAt），缺失时回退当前选中版本消息时间。
     */
    fun toUnreadEntity(node: MessageNode, conversationId: Uuid): ChatUnreadMessageEntity {
        val nodeCreatedAt = if (node.timelineCreatedAt > 0L) {
            node.timelineCreatedAt
        } else {
            node.currentMessage.createdAt
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }
        return ChatUnreadMessageEntity(
            nodeId = node.id.toString(),
            conversationId = conversationId.toString(),
            createdAt = nodeCreatedAt
        )
    }

    /**
     * 未读角标文案：0 → null（不显示角标）；1..99 → 实际数字；>=100 → "99+"。
     */
    fun formatUnreadBadgeCount(count: Int): String? = when {
        count <= 0 -> null
        count >= 100 -> "99+"
        else -> count.toString()
    }
}
