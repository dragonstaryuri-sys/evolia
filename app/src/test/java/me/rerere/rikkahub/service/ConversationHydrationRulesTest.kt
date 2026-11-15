package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.deleteMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationHydrationRulesTest {

    private fun msg(text: String): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0)
    )

    private fun nodeOf(
        conversationId: Uuid,
        vararg messages: UIMessage,
        orderIndex: Int = 0
    ): MessageNode = MessageNode(
        id = Uuid.random(),
        messages = messages.toList(),
        conversationId = conversationId,
        orderIndex = orderIndex
    )

    private fun convWith(nodes: List<MessageNode>): Conversation = Conversation(
        assistantId = Uuid.random(),
        messageNodes = nodes
    )

    // region findUnloadedTargetIds

    @Test
    fun emptyTargetsReturnsEmpty() {
        val conv = convWith(listOf(nodeOf(Uuid.random(), msg("a"))))
        assertTrue(ConversationHydrationRules.findUnloadedTargetIds(conv, emptySet()).isEmpty())
    }

    @Test
    fun allTargetsHydratedReturnsEmpty() {
        val convId = Uuid.random()
        val m1 = msg("a")
        val m2 = msg("b")
        val conv = convWith(
            listOf(
                nodeOf(convId, m1, orderIndex = 0),
                nodeOf(convId, m2, orderIndex = 1)
            )
        )
        val result = ConversationHydrationRules.findUnloadedTargetIds(conv, setOf(m1.id, m2.id))
        assertTrue(result.isEmpty())
    }

    @Test
    fun targetInPlaceholderNodeIsReportedMissing() {
        val convId = Uuid.random()
        val hydrated = msg("recent")
        val placeholder = msg("ancient") // 只存在于 DB,内存中是空占位节点
        val conv = convWith(
            listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = emptyList(), // 空占位节点:历史未加载
                    conversationId = convId,
                    orderIndex = 0
                ),
                nodeOf(convId, hydrated, orderIndex = 1)
            )
        )
        val result = ConversationHydrationRules.findUnloadedTargetIds(conv, setOf(hydrated.id, placeholder.id))
        assertEquals(setOf(placeholder.id), result)
    }

    @Test
    fun nonexistentMessageIsReportedMissing() {
        val conv = convWith(listOf(nodeOf(Uuid.random(), msg("a"))))
        val ghost = Uuid.random()
        assertEquals(setOf(ghost), ConversationHydrationRules.findUnloadedTargetIds(conv, setOf(ghost)))
    }

    // endregion

    // region mergePlaceholderNodes

    @Test
    fun placeholderNodeIsReplacedByHydratedCounterpart() {
        val convId = Uuid.random()
        val placeholderNodeId = Uuid.random()
        val ancient = msg("ancient")
        val hydratedNode = MessageNode(
            id = placeholderNodeId,
            messages = listOf(ancient),
            conversationId = convId,
            orderIndex = 0
        )
        val recentNodeId = Uuid.random()
        val recent = msg("recent")
        val previous = convWith(
            listOf(
                MessageNode(id = placeholderNodeId, messages = emptyList(), conversationId = convId, orderIndex = 0),
                MessageNode(id = recentNodeId, messages = listOf(recent), conversationId = convId, orderIndex = 1)
            )
        )
        val hydratedDb = convWith(
            listOf(
                hydratedNode,
                MessageNode(id = recentNodeId, messages = listOf(recent), conversationId = convId, orderIndex = 1)
            )
        )
        val merged = ConversationHydrationRules.mergePlaceholderNodes(previous, hydratedDb)
        assertEquals(2, merged.messageNodes.size)
        assertEquals(listOf(ancient.id), merged.messageNodes[0].messages.map { it.id })
        assertEquals(recent.id, merged.messageNodes[1].messages.first().id)
    }

    @Test
    fun hydratedInMemoryNodeIsNeverOverwritten() {
        // 内存非空节点持有流式新内容,DB 快照不得覆盖
        val convId = Uuid.random()
        val nodeId = Uuid.random()
        val old = msg("old")
        val streamingNew = msg("streaming-new")
        val previous = convWith(listOf(MessageNode(id = nodeId, messages = listOf(streamingNew), conversationId = convId)))
        val hydratedDb = convWith(listOf(MessageNode(id = nodeId, messages = listOf(old), conversationId = convId)))
        val merged = ConversationHydrationRules.mergePlaceholderNodes(previous, hydratedDb)
        assertEquals(streamingNew.id, merged.messageNodes.first().messages.first().id)
    }

    @Test
    fun hydratedNodeAbsentFromPreviousIsIgnored() {
        val convId = Uuid.random()
        val previous = convWith(
            listOf(MessageNode(id = Uuid.random(), messages = emptyList(), conversationId = convId))
        )
        val hydratedDb = convWith(
            listOf(MessageNode(id = Uuid.random(), messages = listOf(msg("x")), conversationId = convId))
        )
        val merged = ConversationHydrationRules.mergePlaceholderNodes(previous, hydratedDb)
        assertTrue(merged.messageNodes.first().messages.isEmpty())
        assertFalse(merged.messageNodes.size > previous.messageNodes.size)
    }

    @Test
    fun emptyHydratedNodeKeepsPlaceholder() {
        val convId = Uuid.random()
        val nodeId = Uuid.random()
        val previous = convWith(
            listOf(MessageNode(id = nodeId, messages = emptyList(), conversationId = convId))
        )
        val hydratedDb = convWith(
            listOf(MessageNode(id = nodeId, messages = emptyList(), conversationId = convId))
        )
        val merged = ConversationHydrationRules.mergePlaceholderNodes(previous, hydratedDb)
        assertTrue(merged.messageNodes.first().messages.isEmpty())
    }

    @Test
    fun noChangeReturnsOriginalInstance() {
        val convId = Uuid.random()
        val previous = convWith(
            listOf(MessageNode(id = Uuid.random(), messages = listOf(msg("a")), conversationId = convId))
        )
        val hydratedDb = convWith(
            listOf(MessageNode(id = Uuid.random(), messages = listOf(msg("other")), conversationId = convId))
        )
        assertSame(previous, ConversationHydrationRules.mergePlaceholderNodes(previous, hydratedDb))
        assertSame(previous, ConversationHydrationRules.mergePlaceholderNodes(previous, convWith(emptyList())))
    }

    // endregion

    // region 端到端语义:水合 + 删除模型函数协作

    @Test
    fun hydratedDeletionFindsMessageThatWasInPlaceholder() {
        // 回归主场景:老消息位于空占位节点时删除静默失败;水合后 deleteMessages 必须能找到
        val convId = Uuid.random()
        val ancient = msg("ancient")
        val placeholderNodeId = Uuid.random()
        val recent = msg("recent")
        val unloaded = convWith(
            listOf(
                MessageNode(id = placeholderNodeId, messages = emptyList(), conversationId = convId, orderIndex = 0),
                MessageNode(id = Uuid.random(), messages = listOf(recent), conversationId = convId, orderIndex = 1)
            )
        )
        // 未水合:deleteMessages 找不到目标,删除结果为空
        val failedDeletion = unloaded.deleteMessages(setOf(ancient.id))
        assertTrue(failedDeletion.deletedMessageIds.isEmpty())

        // 水合合并后:目标可达,删除生效
        val hydratedDb = convWith(
            listOf(
                MessageNode(
                    id = placeholderNodeId,
                    messages = listOf(ancient),
                    conversationId = convId,
                    orderIndex = 0
                ),
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(recent),
                    conversationId = convId,
                    orderIndex = 1
                )
            )
        )
        val hydrated = ConversationHydrationRules.mergePlaceholderNodes(unloaded, hydratedDb)
        val missing = ConversationHydrationRules.findUnloadedTargetIds(hydrated, setOf(ancient.id))
        assertTrue(missing.isEmpty())
        val deletion = hydrated.deleteMessages(setOf(ancient.id))
        assertEquals(setOf(ancient.id), deletion.deletedMessageIds)
    }

    // endregion
}
