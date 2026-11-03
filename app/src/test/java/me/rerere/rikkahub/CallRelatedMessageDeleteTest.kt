package me.rerere.rikkahub

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.CALL_DECISION_INSTRUCTION
import me.rerere.ai.ui.MessageSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除通话记录时的消息归属判定测试（ConversationRepository.isCallRelatedMessageForDelete）。
 * 覆盖：callId 精确匹配 / call_control 触发轮 / 旧版无 callId 决策消息时间窗口兜底 / 误删防护。
 */
class CallRelatedMessageDeleteTest {

    private val callId = "call_abc123"

    /** 测试基准时间：2026-11-02 12:00:00（本地时区） */
    private val baseTime = LocalDateTime(2026, 11, 2, 12, 0, 0)
    private val baseTimeMs = baseTime.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun messageAt(time: LocalDateTime): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("普通聊天消息")),
        createdAt = time
    )

    // ------------------------------------------------------------------
    // callId 精确匹配
    // ------------------------------------------------------------------

    @Test
    fun `message with matching callId should be deleted`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("我接通了电话")),
            skipContext = true,
            messageSource = MessageSource.CALL_STATE,
            callId = callId
        )
        assertTrue(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    @Test
    fun `message with other callId should not be deleted`() {
        // 其他通话的归属消息不得误删
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("我接通了电话")),
            skipContext = true,
            messageSource = MessageSource.CALL_STATE,
            callId = "call_other999"
        )
        assertFalse(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    // ------------------------------------------------------------------
    // call_control 触发轮（未接通通话，无 callId）
    // ------------------------------------------------------------------

    @Test
    fun `call_control trigger node without callId should be deleted`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("tc1", "call_control", "{}"))
        )
        assertTrue(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    @Test
    fun `other tool call node without callId should not be deleted`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("tc1", "search_web", "{}"))
        )
        assertFalse(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    // ------------------------------------------------------------------
    // 旧版决策消息：USER + skipContext + 固定文案 + 时间窗口
    // ------------------------------------------------------------------

    @Test
    fun `legacy decision message inside time window should be deleted`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = true,
            messageSource = MessageSource.NORMAL,
            createdAt = baseTime
        )
        assertTrue(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    @Test
    fun `legacy decision message outside time window should not be deleted`() {
        // 窗口终点 = callCreateTime + 130s；141s 前的决策消息属于更早的通话，不得误删
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = true,
            messageSource = MessageSource.NORMAL,
            createdAt = baseTime.let { LocalDateTime(2026, 11, 2, 11, 57, 39) } // -141s
        )
        assertFalse(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    @Test
    fun `normal user chat message with same prefix but visible should not be deleted`() {
        // 普通聊天消息（skipContext=false）即使内容一致且在窗口内也不得删除
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = false,
            messageSource = MessageSource.NORMAL,
            createdAt = baseTime
        )
        assertFalse(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }

    @Test
    fun `new CALL_REQUEST decision message with other callId in window should not be deleted`() {
        // 新版决策消息按 callId 归属；其他通话的决策消息即使落在时间窗口内也不得误删
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(CALL_DECISION_INSTRUCTION)),
            skipContext = true,
            messageSource = MessageSource.CALL_REQUEST,
            callId = "call_other999",
            createdAt = baseTime
        )
        assertFalse(ConversationRepository.isCallRelatedMessageForDelete(msg, callId, baseTimeMs))
    }
}
