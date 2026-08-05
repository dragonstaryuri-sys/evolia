package me.rerere.rikkahub

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.deleteMessages
import me.rerere.rikkahub.core.data.model.normalizeMessageNodes
import me.rerere.rikkahub.core.data.model.removeInvalidMessages
import me.rerere.rikkahub.core.data.model.restoreMessagesFrom
import me.rerere.rikkahub.service.selectMessagesForGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessageMutationTest {
    private val conversationId = Uuid.random()
    private val assistantId = Uuid.random()

    @Test
    fun `normalization assigns every generated tool node a unique order index`() {
        val nodes = listOf(
            node(UIMessage.user("question"), orderIndex = 0),
            node(toolCall("v2"), orderIndex = 0),
            node(UIMessage.assistant("answer").copy(versionTag = "v2"), orderIndex = 0)
        )
        val conversation = conversation(nodes).normalizeMessageNodes()

        assertEquals(listOf(0, 1, 2), conversation.messageNodes.map { it.orderIndex })
        assertTrue(conversation.messageNodes.all { it.conversationId == conversationId })
    }

    @Test
    fun `multi-select deletion removes nodes in one immutable pass and reindexes survivors`() {
        val first = node(UIMessage.user("one"), orderIndex = 0)
        val second = node(UIMessage.assistant("two"), orderIndex = 1)
        val survivor = node(UIMessage.user("three"), orderIndex = 2)
        val deletion = conversation(listOf(first, second, survivor)).deleteMessages(
            setOf(first.currentMessage.id, second.currentMessage.id)
        )

        assertEquals(setOf(first.id, second.id), deletion.deletedNodeIds)
        assertEquals(listOf(survivor.id), deletion.conversation.messageNodes.map { it.id })
        assertEquals(listOf(0), deletion.conversation.messageNodes.map { it.orderIndex })
    }

    @Test
    fun `message deletion preserves unloaded history placeholders`() {
        val placeholder = MessageNode(
            messages = emptyList(),
            conversationId = conversationId,
            orderIndex = 0,
            timelineCreatedAt = 1L
        )
        val selected = node(UIMessage.user("delete me"), orderIndex = 1)
        val survivor = node(UIMessage.assistant("keep me"), orderIndex = 2)

        val deletion = conversation(listOf(placeholder, selected, survivor))
            .deleteMessages(setOf(selected.currentMessage.id))

        assertEquals(
            listOf(placeholder.id, survivor.id),
            deletion.conversation.messageNodes.map { it.id }
        )
        assertEquals(setOf(selected.id), deletion.deletedNodeIds)
        assertEquals(listOf(0, 1), deletion.conversation.messageNodes.map { it.orderIndex })
    }

    @Test
    fun `invalid message cleanup preserves unloaded history placeholders`() {
        val placeholder = MessageNode(
            messages = emptyList(),
            conversationId = conversationId,
            orderIndex = 0,
            timelineCreatedAt = 1L
        )
        val user = node(UIMessage.user("question"), orderIndex = 1)
        val blankAssistant = node(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            orderIndex = 2
        )

        val cleaned = conversation(listOf(placeholder, user, blankAssistant))
            .removeInvalidMessages()

        assertEquals(listOf(placeholder.id, user.id), cleaned.messageNodes.map { it.id })
        assertEquals(listOf(0, 1), cleaned.messageNodes.map { it.orderIndex })
    }

    @Test
    fun `undo restores deleted nodes without overwriting newer nodes`() {
        val user = node(UIMessage.user("question"), orderIndex = 0)
        val deletedAnswer = node(UIMessage.assistant("deleted answer"), orderIndex = 1)
        val newerNode = node(UIMessage.user("newer message"), orderIndex = 1)
        val backup = conversation(listOf(user, deletedAnswer))
        val current = conversation(listOf(user, newerNode))

        val restored = current.restoreMessagesFrom(backup)

        assertEquals(
            listOf(user.id, deletedAnswer.id, newerNode.id),
            restored.messageNodes.map { it.id }
        )
        assertEquals(listOf(0, 1, 2), restored.messageNodes.map { it.orderIndex })
    }

    @Test
    fun `deleting regenerated message removes linked tool branch but preserves old version`() {
        val userNode = node(UIMessage.user("question"), orderIndex = 0)
        val oldAnswer = UIMessage.assistant("old answer")
        val regenerated = toolCall("regenerated")
        val versionedNode = MessageNode(
            messages = listOf(oldAnswer, regenerated),
            selectIndex = 1,
            conversationId = conversationId,
            orderIndex = 1
        )
        val toolResultNode = node(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call-1",
                        toolName = "test_tool",
                        content = kotlinx.serialization.json.JsonObject(emptyMap()),
                        arguments = kotlinx.serialization.json.JsonObject(emptyMap())
                    )
                ),
                versionTag = "regenerated"
            ),
            orderIndex = 2
        )
        val finalNode = node(
            UIMessage.assistant("new answer").copy(versionTag = "regenerated"),
            orderIndex = 3
        )

        val deletion = conversation(
            listOf(userNode, versionedNode, toolResultNode, finalNode)
        ).deleteMessages(setOf(regenerated.id))

        assertEquals(listOf(userNode.id, versionedNode.id), deletion.conversation.messageNodes.map { it.id })
        assertEquals(listOf(oldAnswer), deletion.conversation.messageNodes.last().messages)
        assertEquals(0, deletion.conversation.messageNodes.last().selectIndex)
        assertEquals(setOf(toolResultNode.id, finalNode.id), deletion.deletedNodeIds)
    }

    @Test
    fun `refresh context range remains valid after conversation truncation`() {
        val messages = listOf(
            UIMessage.user("old user"),
            UIMessage.assistant("old answer"),
            UIMessage.user("latest user"),
            UIMessage.assistant("placeholder")
        )
        val nodes = messages.mapIndexed { index, message -> node(message, orderIndex = index) }

        val context = selectMessagesForGeneration(
            messageNodes = nodes,
            contextEndNodeId = nodes[2].id,
            truncateIndex = 2
        )
        val fullyTruncatedRange = selectMessagesForGeneration(
            messageNodes = nodes,
            contextEndNodeId = nodes[1].id,
            truncateIndex = 3
        )

        assertEquals(listOf(messages[2]), context)
        assertTrue(fullyTruncatedRange.isEmpty())
    }

    @Test
    fun `refresh context boundary does not include future turns when placeholders are present`() {
        val placeholders = List(3) { index ->
            MessageNode(
                messages = emptyList(),
                conversationId = conversationId,
                orderIndex = index,
                timelineCreatedAt = index + 1L
            )
        }
        val targetUser = node(UIMessage.user("target question"), orderIndex = 3)
        val targetPlaceholder = node(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            orderIndex = 4
        )
        val futureUser = node(UIMessage.user("future question"), orderIndex = 5)
        val futureAnswer = node(UIMessage.assistant("future answer"), orderIndex = 6)
        val nodes = placeholders + targetUser + targetPlaceholder + futureUser + futureAnswer
        val context = selectMessagesForGeneration(
            messageNodes = nodes,
            contextEndNodeId = targetUser.id,
            truncateIndex = 0
        ).filter { message ->
            message.role != MessageRole.ASSISTANT || message.parts.isNotEmpty()
        }

        assertEquals(listOf(targetUser.currentMessage), context)
    }

    @Test
    fun `refresh context fails closed when its boundary node is missing`() {
        val nodes = listOf(
            node(UIMessage.user("question"), orderIndex = 0),
            node(UIMessage.assistant("answer"), orderIndex = 1)
        )

        val context = selectMessagesForGeneration(
            messageNodes = nodes,
            contextEndNodeId = Uuid.random(),
            truncateIndex = 0
        )

        assertTrue(context.isEmpty())
    }

    private fun toolCall(versionTag: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.ToolCall(
                toolCallId = "call-1",
                toolName = "test_tool",
                arguments = "{}"
            )
        ),
        versionTag = versionTag
    )

    private fun node(message: UIMessage, orderIndex: Int): MessageNode = MessageNode(
        messages = listOf(message),
        conversationId = conversationId,
        orderIndex = orderIndex
    )

    private fun conversation(nodes: List<MessageNode>): Conversation = Conversation(
        id = conversationId,
        assistantId = assistantId,
        messageNodes = nodes
    )
}
