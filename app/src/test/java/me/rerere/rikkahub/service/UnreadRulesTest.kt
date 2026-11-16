package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 未读消息角标纯决策函数单测（UnreadRules）。
 * 覆盖：角标文案格式化 / 可见性判定（角色、skipContext、内容）/ 候选挑选（基线、排除集）。
 */
class UnreadRulesTest {

    private val conversationId = Uuid.random()

    private fun textMessage(text: String, skipContext: Boolean = false) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0),
        skipContext = skipContext
    )

    private fun nodeOf(vararg messages: UIMessage, selectIndex: Int = 0, timelineCreatedAt: Long = 1000L): MessageNode {
        return MessageNode(
            id = Uuid.random(),
            messages = messages.toList(),
            selectIndex = selectIndex,
            conversationId = conversationId,
            timelineCreatedAt = timelineCreatedAt
        )
    }

    // ==================== formatUnreadBadgeCount ====================

    @Test
    fun `badge format - zero hides badge`() {
        assertNull(UnreadRules.formatUnreadBadgeCount(0))
        assertNull(UnreadRules.formatUnreadBadgeCount(-1))
    }

    @Test
    fun `badge format - 1 to 99 shows number`() {
        assertEquals("1", UnreadRules.formatUnreadBadgeCount(1))
        assertEquals("5", UnreadRules.formatUnreadBadgeCount(5))
        assertEquals("99", UnreadRules.formatUnreadBadgeCount(99))
    }

    @Test
    fun `badge format - 100 and above shows 99+`() {
        assertEquals("99+", UnreadRules.formatUnreadBadgeCount(100))
        assertEquals("99+", UnreadRules.formatUnreadBadgeCount(12345))
    }

    // ==================== isVisibleAssistantNode ====================

    @Test
    fun `visible - plain text assistant node`() {
        val node = nodeOf(textMessage("你好呀"))
        assertTrue(UnreadRules.isVisibleAssistantNode(node))
    }

    @Test
    fun `visible - blank assistant placeholder node`() {
        // 空占位节点（messages 为空）：不可见
        assertFalse(
            UnreadRules.isVisibleAssistantNode(
                MessageNode(
                    id = Uuid.random(),
                    messages = emptyList(),
                    conversationId = conversationId
                )
            )
        )
    }

    @Test
    fun `not visible - user role`() {
        val node = nodeOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("用户消息")))
        )
        assertFalse(UnreadRules.isVisibleAssistantNode(node))
    }

    @Test
    fun `not visible - tool role`() {
        val node = nodeOf(
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("工具结果")))
        )
        assertFalse(UnreadRules.isVisibleAssistantNode(node))
    }

    @Test
    fun `not visible - skipContext hidden messages`() {
        // 通话转写 / 通话状态 / 自动化指令等全部带 skipContext=true
        listOf(
            textMessage("通话转写", skipContext = true),
            textMessage("（我挂断了电话）", skipContext = true),
            textMessage("【系统自动化指令】", skipContext = true)
        ).forEach { message ->
            assertFalse("skipContext 消息不应计入未读: ${message.toContentText()}", UnreadRules.isVisibleAssistantNode(nodeOf(message)))
        }
    }

    @Test
    fun `not visible - blank text without media`() {
        // 纯思考过程 / 纯工具调用节点：文本空白且无音频图片
        val reasoningOnly = nodeOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(reasoning = "思考内容")),
                createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
            )
        )
        assertFalse(UnreadRules.isVisibleAssistantNode(reasoningOnly))
    }

    @Test
    fun `not visible - tool call only assistant node`() {
        val toolCallOnly = nodeOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall(
                        toolCallId = "call-1",
                        toolName = "search_web",
                        arguments = """{"query":"test"}"""
                    )
                ),
                createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
            )
        )
        assertFalse(UnreadRules.isVisibleAssistantNode(toolCallOnly))
    }

    @Test
    fun `visible - audio only node counts as voice bubble`() {
        val voiceNode = nodeOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Audio(url = "file:///voice.m4a")),
                createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
            )
        )
        assertTrue(UnreadRules.isVisibleAssistantNode(voiceNode))
    }

    @Test
    fun `visible - image only node`() {
        val imageNode = nodeOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Image(url = "file:///img.png")),
                createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
            )
        )
        assertTrue(UnreadRules.isVisibleAssistantNode(imageNode))
    }

    @Test
    fun `visible - only selected version counts`() {
        // 多版本节点：select_index 指向的当前选中版本决定可见性；
        // 未被选中的历史回复版本不参与判定
        val oldVersion = textMessage("旧版本回复")
        val newVersion = textMessage("新版本回复")
        val node = nodeOf(oldVersion, newVersion, selectIndex = 1)
        assertTrue(UnreadRules.isVisibleAssistantNode(node))
        // 当前选中版本是 skipContext 的旧版本 → 整个节点不可见
        val hiddenSelected = nodeOf(
            textMessage("隐藏版本", skipContext = true),
            textMessage("可见版本"),
            selectIndex = 0
        )
        assertFalse(UnreadRules.isVisibleAssistantNode(hiddenSelected))
    }

    // ==================== pickUnreadCandidates ====================

    @Test
    fun `candidates - placeholder reuse counts as new`() {
        // 复刻真实链路：AI 回复复用生成前的空占位节点 id。
        // 基线（生成开始）该节点无可见内容 → 不在基线集合；生成后可见 → 计入未读。
        val placeholderId = Uuid.random()
        val baselineNode = MessageNode(
            id = placeholderId,
            messages = emptyList(),
            conversationId = conversationId,
            timelineCreatedAt = 5000L
        )
        val baselineConversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(baselineNode)
        )
        val baselineIds = baselineConversation.messageNodes
            .filter { UnreadRules.isVisibleAssistantNode(it) }
            .mapTo(mutableSetOf()) { it.id }
        assertTrue(baselineIds.isEmpty())

        val finalNode = baselineNode.copy(messages = listOf(textMessage("最终回复")))
        val finalConversation = baselineConversation.copy(messageNodes = listOf(finalNode))

        val candidates = UnreadRules.pickUnreadCandidates(finalConversation, baselineIds)
        assertEquals(1, candidates.size)
        assertEquals(placeholderId.toString(), candidates[0].nodeId)
        assertEquals(conversationId.toString(), candidates[0].conversationId)
    }

    @Test
    fun `candidates - refreshed version on existing visible node not counted`() {
        // 刷新回复是在已有可见内容的节点上追加版本：节点已在基线集合内，不重复计数
        val nodeId = Uuid.random()
        val existingNode = MessageNode(
            id = nodeId,
            messages = listOf(textMessage("旧版本")),
            selectIndex = 0,
            conversationId = conversationId,
            timelineCreatedAt = 5000L
        )
        val baselineIds = setOf(nodeId)
        val refreshed = existingNode.copy(
            messages = listOf(textMessage("旧版本"), textMessage("新版本")),
            selectIndex = 1
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(refreshed)
        )
        val candidates = UnreadRules.pickUnreadCandidates(conversation, baselineIds)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `candidates - wechat sentences counted per node`() {
        // 微信模式一次生成多个独立可见节点：逐节点计数
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(
                nodeOf(textMessage("第一句")),
                nodeOf(textMessage("第二句")),
                nodeOf(textMessage("第三句"))
            )
        )
        val candidates = UnreadRules.pickUnreadCandidates(conversation, emptySet())
        assertEquals(3, candidates.size)
    }

    @Test
    fun `candidates - tool nodes excluded from count`() {
        // 一轮生成：中间的工具调用节点（不可见）+ 工具结果节点（不可见）+ 最终回答节点（可见）
        // → 只计最终回答，工具执行过程不产生未读
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(
                nodeOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.ToolCall(
                                toolCallId = "call-1",
                                toolName = "search_web",
                                arguments = "{}"
                            )
                        ),
                        createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
                    )
                ),
                nodeOf(UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("结果")))),
                nodeOf(textMessage("最终回答"))
            )
        )
        val candidates = UnreadRules.pickUnreadCandidates(conversation, emptySet())
        assertEquals(1, candidates.size)
    }

    @Test
    fun `candidates - excluded handled ids skipped`() {
        // 微信逐句路径已决策过的节点（onSuccess 汇总时排除），防止重复决策
        val handledNode = nodeOf(textMessage("已处理分句"))
        val freshNode = nodeOf(textMessage("新节点"))
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(handledNode, freshNode)
        )
        val candidates = UnreadRules.pickUnreadCandidates(
            conversation,
            baselineVisibleNodeIds = emptySet(),
            excludeNodeIds = setOf(handledNode.id)
        )
        assertEquals(1, candidates.size)
        assertEquals(freshNode.id.toString(), candidates[0].nodeId)
    }

    @Test
    fun `candidates - entity created from timelineCreatedAt`() {
        val node = nodeOf(textMessage("带时间戳"), timelineCreatedAt = 1700000000000L)
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(node)
        )
        val candidates = UnreadRules.pickUnreadCandidates(conversation, emptySet())
        assertEquals(1, candidates.size)
        assertEquals(1700000000000L, candidates[0].createdAt)
    }

    @Test
    fun `candidates - empty conversation yields nothing`() {
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = emptyList()
        )
        assertTrue(UnreadRules.pickUnreadCandidates(conversation, emptySet()).isEmpty())
    }

    @Test
    fun `candidates - fallback createdAt uses message time when timeline missing`() {
        // timelineCreatedAt 缺失（0）时回退当前选中版本消息时间
        val createdAt = LocalDateTime(2026, 11, 16, 12, 0, 0)
        val expectedMs = createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val node = MessageNode(
            id = Uuid.random(),
            messages = listOf(textMessage("无时间线游标")),
            selectIndex = 0,
            conversationId = conversationId,
            timelineCreatedAt = 0L
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.random(),
            messageNodes = listOf(node)
        )
        val candidates = UnreadRules.pickUnreadCandidates(conversation, emptySet())
        assertEquals(1, candidates.size)
        assertEquals(expectedMs, candidates[0].createdAt)
    }

    @Test
    fun `toMessageNode keeps visibility semantics`() {
        // 由 UIMessage 构建的节点（sendMessage / 微信分句路径）可见性判定可用
        val message = textMessage("分句内容")
        val node = message.toMessageNode(conversationId)
        assertTrue(UnreadRules.isVisibleAssistantNode(node))
    }
}
