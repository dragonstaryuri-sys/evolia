package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Responses API 输入清洗（cleanResponseApiInput）测试：
 * function_call / function_call_output 必须严格配对，孤立项会被 OpenAI 拒绝
 * 或导致模型看不到工具结果而反复调用。
 */
class ResponseAPICleanTest {

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text))
    )

    private fun assistantWithCalls(vararg calls: UIMessagePart.ToolCall, text: String = ""): UIMessage {
        val parts = buildList {
            if (text.isNotBlank()) add(UIMessagePart.Text(text))
            addAll(calls)
        }
        return UIMessage(role = MessageRole.ASSISTANT, parts = parts)
    }

    private fun toolResult(id: String, name: String = "tool") = UIMessage(
        role = MessageRole.TOOL,
        parts = listOf(
            UIMessagePart.ToolResult(
                toolCallId = id,
                toolName = name,
                content = JsonPrimitive("result of $id"),
                arguments = JsonPrimitive("{}")
            )
        )
    )

    @Test
    fun `fully paired sequence passes through unchanged`() {
        val messages = listOf(
            user("hi"),
            assistantWithCalls(UIMessagePart.ToolCall("call_1", "search_web", "{}")),
            toolResult("call_1"),
        )
        assertEquals(messages, messages.cleanResponseApiInput())
    }

    @Test
    fun `orphan tool result without matching call is dropped`() {
        val messages = listOf(
            user("hi"),
            toolResult("orphan_1"),
        )
        val cleaned = messages.cleanResponseApiInput()
        assertNull(cleaned.firstOrNull { it.role == MessageRole.TOOL })
    }

    @Test
    fun `partially orphaned tool results are filtered keeping paired ones`() {
        val messages = listOf(
            user("hi"),
            assistantWithCalls(UIMessagePart.ToolCall("call_1", "search_web", "{}")),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1", toolName = "search_web",
                        content = JsonPrimitive("ok"), arguments = JsonPrimitive("{}")
                    ),
                    UIMessagePart.ToolResult(
                        toolCallId = "lost_id", toolName = "search_web",
                        content = JsonPrimitive("orphan"), arguments = JsonPrimitive("{}")
                    ),
                )
            ),
        )
        val cleaned = messages.cleanResponseApiInput()
        val toolMsg = checkNotNull(cleaned.firstOrNull { it.role == MessageRole.TOOL })
        assertEquals(1, toolMsg.getToolResults().size)
        assertEquals("call_1", toolMsg.getToolResults().first().toolCallId)
    }

    @Test
    fun `unpaired tool call is stripped from assistant keeping text`() {
        val messages = listOf(
            user("hi"),
            assistantWithCalls(
                UIMessagePart.ToolCall("call_1", "search_web", "{}"),
                UIMessagePart.ToolCall("lost_id", "search_web", "{}"),
                text = "让我搜索一下"
            ),
            toolResult("call_1"),
        )
        val cleaned = messages.cleanResponseApiInput()
        val assistant = checkNotNull(cleaned.firstOrNull { it.role == MessageRole.ASSISTANT })
        assertEquals(1, assistant.getToolCalls().size)
        assertEquals("call_1", assistant.getToolCalls().first().toolCallId)
        // 文本内容保留
        assertEquals("让我搜索一下", assistant.parts.filterIsInstance<UIMessagePart.Text>().first().text)
    }

    @Test
    fun `blank id tool result is treated as orphan and dropped`() {
        val messages = listOf(
            user("hi"),
            assistantWithCalls(UIMessagePart.ToolCall("call_1", "search_web", "{}")),
            toolResult("call_1"),
            toolResult("", "search_web"),
        )
        val cleaned = messages.cleanResponseApiInput()
        // 第二条 TOOL 消息（blank id）整条被丢弃，第一条正常保留
        val toolMessages = cleaned.filter { it.role == MessageRole.TOOL }
        assertEquals(1, toolMessages.size)
        assertEquals("call_1", toolMessages.first().getToolResults().first().toolCallId)
    }

    @Test
    fun `plain conversation without tools is untouched`() {
        val messages = listOf(
            user("hi"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hello"))),
        )
        assertEquals(messages, messages.cleanResponseApiInput())
    }
}
