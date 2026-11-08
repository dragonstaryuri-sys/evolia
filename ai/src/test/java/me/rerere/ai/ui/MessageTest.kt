package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ------------------------------------------------------------------
    // 通话决策指令消息判定（isCallDecisionRequest）
    // 用于后续对话上下文剔除与删除通话记录时的关联删除
    // ------------------------------------------------------------------

    @Test
    fun `isCallDecisionRequest should match CALL_REQUEST decision message`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = true,
            messageSource = MessageSource.CALL_REQUEST,
            callId = "call_abc123"
        )
        assertTrue(msg.isCallDecisionRequest())
    }

    @Test
    fun `isCallDecisionRequest should match legacy NORMAL decision message without callId`() {
        // 旧版本遗留决策消息：无 callId、NORMAL 来源、skipContext、固定决策文案
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = true,
            messageSource = MessageSource.NORMAL
        )
        assertTrue(msg.isCallDecisionRequest())
    }

    @Test
    fun `isCallDecisionRequest should not match normal user chat message`() {
        // 正常聊天消息 skipContext=false；即使手动粘贴了指令文案也不应命中
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = false,
            messageSource = MessageSource.NORMAL
        )
        assertFalse(msg.isCallDecisionRequest())
    }

    @Test
    fun `isCallDecisionRequest should not match similar text without full instruction prefix`() {
        // 内容相似但非固定决策文案的 skipContext 消息不应命中
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("用户发起了通话请求，请回复 0 或 1，表示挂断或接通。")),
            skipContext = true,
            messageSource = MessageSource.NORMAL
        )
        assertFalse(msg.isCallDecisionRequest())
    }

    @Test
    fun `isCallDecisionRequest should not match assistant CALL_STATE marker`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("我接通了电话")),
            skipContext = true,
            messageSource = MessageSource.CALL_STATE,
            callId = "call_abc123"
        )
        assertFalse(msg.isCallDecisionRequest())
    }

    // ------------------------------------------------------------------
    // 流式 ToolCall 合并（handleMessageChunk）
    // Gemini 等协议的 functionCall 无 toolCallId，并行调用与参数续传必须正确区分，
    // 否则会把并行调用错并成一个（拼出坏工具名），模型看不到工具结果而反复重调。
    // ------------------------------------------------------------------

    private fun toolCallChunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null
            )
        )
    )

    private fun mergeToolCallChunks(chunks: List<MessageChunk>): List<UIMessagePart> {
        var messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))))
        chunks.forEach { chunk ->
            messages = messages.handleMessageChunk(chunk)
        }
        return messages.last().parts
    }

    @Test
    fun `gemini parallel tool calls with different names should stay separate`() {
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("", "search_web", """{"query":"a"}""")),
                toolCallChunk(UIMessagePart.ToolCall("", "read_url", """{"url":"b"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals("search_web", calls[0].toolName)
        assertEquals("read_url", calls[1].toolName)
    }

    @Test
    fun `gemini parallel same-name tool calls with complete args should stay separate`() {
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("", "search_web", """{"query":"a"}""")),
                toolCallChunk(UIMessagePart.ToolCall("", "search_web", """{"query":"b"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals("""{"query":"a"}""", calls[0].arguments)
        assertEquals("""{"query":"b"}""", calls[1].arguments)
    }

    @Test
    fun `blank-id argument fragments should merge into one call`() {
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("", "", """{"query":""")),
                toolCallChunk(UIMessagePart.ToolCall("", "", """"weather"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("""{"query":"weather"}""", calls[0].arguments)
    }

    @Test
    fun `blank-name fragment after complete first fragment should merge`() {
        // 首个分片已是完整 JSON，后续分片为续传片段：应按参数续传合并而非拆成两个调用
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("", "search_web", "{}")),
                toolCallChunk(UIMessagePart.ToolCall("", "", """"query":"a"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("""{"query":"a"}""", calls[0].arguments)
    }

    @Test
    fun `same-id repeated full object should not duplicate tool name`() {
        // 部分供应商流式续传时按相同 id 重复下发完整对象，名称不能被拼接成 "search_websearch_web"
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("call_1", "search_web", "")),
                toolCallChunk(UIMessagePart.ToolCall("call_1", "search_web", """{"query":"a"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("search_web", calls[0].toolName)
        assertEquals("""{"query":"a"}""", calls[0].arguments)
    }

    @Test
    fun `openai-style id-carrying parallel calls merge by id`() {
        val parts = mergeToolCallChunks(
            listOf(
                toolCallChunk(UIMessagePart.ToolCall("call_1", "search_web", """{"query":""")),
                toolCallChunk(UIMessagePart.ToolCall("call_2", "read_url", "")),
                toolCallChunk(UIMessagePart.ToolCall("call_1", "", """"weather"}""")),
                toolCallChunk(UIMessagePart.ToolCall("call_2", "", """{"url":"b"}""")),
            )
        )
        val calls = parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals("call_1", calls[0].toolCallId)
        assertEquals("""{"query":"weather"}""", calls[0].arguments)
        assertEquals("call_2", calls[1].toolCallId)
        assertEquals("""{"url":"b"}""", calls[1].arguments)
    }
}
