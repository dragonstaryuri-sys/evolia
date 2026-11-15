package me.rerere.rikkahub.ui.components.chat

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * groupIntoTurns 的时间断档判定必须基于节点创建时间(timelineCreatedAt),
 * 而不是当前显示消息的时间:编辑旧消息会向节点追加 createdAt = 编辑时刻的
 * 新 version,按消息时间判定会把编辑过的节点拆成独立 turn 并排到时间线最新位置。
 */
class GroupIntoTurnsTest {

    private val convId = Uuid.random()

    private fun msg(role: MessageRole, createdAt: LocalDateTime): UIMessage = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text("m")),
        createdAt = createdAt
    )

    private fun node(
        timelineMs: Long,
        messageCreatedAt: LocalDateTime = LocalDateTime(2026, 1, 1, 12, 0, 0, 0),
        role: MessageRole = MessageRole.USER
    ): MessageNode = MessageNode(
        id = Uuid.random(),
        messages = listOf(msg(role, messageCreatedAt)),
        conversationId = convId,
        timelineCreatedAt = timelineMs
    )

    // 2026-01-01 12:00 (本地) 的 epoch ms 基准,由第一条消息时间推导,避免时区硬编码
    private val baseMs: Long =
        LocalDateTime(2026, 1, 1, 12, 0, 0, 0)
            .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
            .toEpochMilliseconds()

    private fun at(minutes: Long) = LocalDateTime(2026, 1, 1, 12, 0, 0, 0)
        .let { baseMs + minutes * 60_000L }

    @Test
    fun editedOldNodeStaysInSameTurn() {
        // 两节点 timeline 连续(差 1 分钟);后一节点被编辑,currentMessage.createdAt = 编辑时刻(60 分钟后)
        val older = node(timelineMs = at(0), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0))
        val edited = node(
            timelineMs = at(1),
            messageCreatedAt = LocalDateTime(2026, 1, 1, 13, 0, 0, 0) // 编辑时刻,突变
        )
        val groups = listOf(older, edited).groupIntoTurns() // pageNodes 时间升序
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().nodes.size)
    }

    @Test
    fun timelineGapOverFiveMinutesSplitsTurn() {
        val older = node(timelineMs = at(0), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0))
        val newer = node(timelineMs = at(10), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 10, 0, 0))
        val groups = listOf(older, newer).groupIntoTurns()
        assertEquals(2, groups.size)
    }

    @Test
    fun timelineGapWithinFiveMinutesStaysTogether() {
        val older = node(timelineMs = at(0), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0))
        val newer = node(timelineMs = at(4), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 4, 0, 0))
        val groups = listOf(older, newer).groupIntoTurns()
        assertEquals(1, groups.size)
    }

    @Test
    fun zeroTimelineCreatedAtFallsBackToMessageTime() {
        // 旧数据/内存构造可能没有 timelineCreatedAt(= 0),回退消息时间判定
        val older = node(timelineMs = 0L, messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0))
        val edited = node(timelineMs = 0L, messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 1, 0, 0))
        val groups = listOf(older, edited).groupIntoTurns()
        assertEquals(1, groups.size)
    }

    @Test
    fun zeroTimelineCreatedAtStillSplitsOnMessageGap() {
        val older = node(timelineMs = 0L, messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0))
        val newer = node(timelineMs = 0L, messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 30, 0, 0))
        val groups = listOf(older, newer).groupIntoTurns()
        assertEquals(2, groups.size)
    }

    @Test
    fun roleChangeStillSplitsTurn() {
        val user = node(timelineMs = at(0), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 0, 0, 0), role = MessageRole.USER)
        val assistant = node(timelineMs = at(1), messageCreatedAt = LocalDateTime(2026, 1, 1, 12, 1, 0, 0), role = MessageRole.ASSISTANT)
        val groups = listOf(user, assistant).groupIntoTurns()
        assertEquals(2, groups.size)
    }
}
