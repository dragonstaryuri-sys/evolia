package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    // ------------------------------------------------------------------
    // 引号 / 特殊字符序列化安全测试
    // 验证：正常序列化路径下，带引号/换行/控制字符都不会破坏 JSON 结构
    // ------------------------------------------------------------------

    @Test
    fun `UIMessagePart Text with embedded double quotes should roundtrip`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text = "他说：\"你好，世界。\" 然后笑了"))
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UIMessage>(encoded)
        assertEquals("他说：\"你好，世界。\" 然后笑了",
            (decoded.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `UIMessagePart Text with multiple quotes and escape sequences should roundtrip`() {
        val text = """引号"引号'反斜杠\换\n行回\r车制\t表"""
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text = text))
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UIMessage>(encoded)
        assertEquals(text, (decoded.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `UIMessagePart Text containing JSON like metadata pattern should roundtrip`() {
        // 模拟用户的报错片段："消息的消息","metadata":null,"priority":0 这个结构
        val suspicious = "爱心呢，安静又凉快，多好。起来吧，我等你出门的消息 \",\"metadata\":null,\"priority\":0}],\""
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text = suspicious))
        )
        val encoded = json.encodeToString(msg)
        // 正常序列化后，text 内部的引号必须被转义
        assertTrue("encoded json must escape internal quotes",
            encoded.contains("我等你出门的消息 \\\",\\\"metadata"))
        val decoded = json.decodeFromString<UIMessage>(encoded)
        assertEquals(suspicious, (decoded.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `simulate corrupted JSON - exactly like crash report should throw JsonDecodingException`() {
        // 手工构造一个写坏的 JSON（模拟 content_json 中途被截断/被外部修改），
        // 精确复现用户的两条报错：text 内容中出现未转义的 " 导致提前闭合，
        // 后续本来属于 text 的内容变成 JSON 结构，解析器期待 key 的引号却遇到了 'm'（metadata 的首字母）
        val corrupted = "{\"id\":\"00000000-0000-0000-0000-000000000000\",\"role\":\"ASSISTANT\",\"parts\":[{\"type\":\"me.rerere.ai.ui.UIMessagePart.Text\",\"text\":\"起来吧，我等你出门的消息 \",\"metadata\":null,\"priority\":0}],\"annotations\":[],\"createdAt\":\"2026-01-01T00:00\"}"
        //                                           ↑↑ 这里 text 内容结尾有一个原始的 " 字符没有被转义
        // 解析器顺序：读完 "text":"... 后遇到内部 " → 误认为 text 字符串结束 → 接下来期待 , 或 }
        // 但实际遇到的是 `,"metadata"...`，解析器把它当成下一个 key，然后期望 key 的引号前遇到 m 了，
        // 所以报错 Expected quotation mark '"', but had 'm'
        val result = runCatching { json.decodeFromString<UIMessage>(corrupted) }
        assertTrue("should fail decoding with JsonDecodingException, result=$result",
            result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        println("DECODE ERROR MSG: $msg")
        assertTrue("error should mention either Unexpected JSON token or Expected quotation mark, actual: $msg",
            msg.contains("Expected") || msg.contains("Unexpected"))
        assertTrue("error should point to parts/text, actual: $msg",
            (msg.contains("parts") || msg.contains("text")))
    }

    @Test
    fun `truncateLastAssistantMessage style substring should not produce invalid chars`() {
        // 模拟：AI返回带引号的文本，然后 truncate 截取到引号前一个字符
        val original = "他说：\"起床啦\"，我等你出门"
        val cutLen = 5  // 正好截在：他说："
        val cut = original.take(cutLen)
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text = cut))
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UIMessage>(encoded)
        assertEquals(cut, (decoded.parts[0] as UIMessagePart.Text).text)
    }


    @Test
    fun `limitContext with size 0 should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(0)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with negative size should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(-1)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with size greater than list size should return original list`() {
        val messages = createTestMessages(3)
        val result = messages.limitContext(5)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with normal size should return last N messages`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(3)
        assertEquals(3, result.size)
        assertEquals(messages.subList(2, 5), result)
    }

    @Test
    fun `limitContext with tool result at start should include corresponding tool call`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User message"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool call at start should include corresponding user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool result that chains to tool call and user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Initial query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Response 1"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Response 2")))
        )

        // Request only 1 message but tool result should chain back to include user message
        val result = messages.limitContext(1)
        assertEquals(1, result.size)
        assertEquals(messages.subList(4, 5), result)
    }

    @Test
    fun `limitContext with multiple tool calls should find earliest user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "tool1", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call2", "tool2", "{}")
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool result but no corresponding tool call should not adjust`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User 1"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 1"))),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("orphan", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 2")))
        )

        val result = messages.limitContext(2)
        assertEquals(2, result.size)
        assertEquals(messages.subList(2, 4), result)
    }

    @Test
    fun `limitContext with tool call but no corresponding user message should not adjust further`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 1"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 2")))
        )

        val result = messages.limitContext(2)
        assertEquals(2, result.size)
        assertEquals(messages.subList(1, 3), result)
    }

    @Test
    fun `limitContext with empty list should return empty list`() {
        val messages = emptyList<UIMessage>()
        val result = messages.limitContext(5)
        assertEquals(emptyList<UIMessage>(), result)
    }

    @Test
    fun `limitContext with single message should return that message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Single message")))
        )
        val result = messages.limitContext(1)
        assertEquals(1, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with complex chain of tool calls and results`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Initial query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "tool1", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call2", "tool2", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call2", "tool2", JsonPrimitive("result2"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        // Request 3 messages starting from tool result, should include the whole chain
        val result = messages.limitContext(3)
        assertEquals(6, result.size)
        assertEquals(messages, result)
    }

    private fun createTestMessages(count: Int): List<UIMessage> {
        return (0 until count).map { i ->
            UIMessage(
                role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Message $i"))
            )
        }
    }
}
