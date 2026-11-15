package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeArchiveChunkRulesTest {

    private fun msg(text: String, tsMillis: Long): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime(
            2026, 1, 1,
            (tsMillis / 3_600_000).toInt() % 24,
            ((tsMillis / 60_000) % 60).toInt(),
            ((tsMillis / 1_000) % 60).toInt(),
            (tsMillis % 1_000).toInt() * 1_000_000
        )
    )

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(splitEpisodeArchiveChunks(emptyList(), maxChars = 100, maxMessages = 10).isEmpty())
    }

    @Test
    fun singleOversizedMessageFormsItsOwnChunk() {
        val huge = msg("a".repeat(500), tsMillis = 1_000)
        val chunks = splitEpisodeArchiveChunks(listOf(huge), maxChars = 100, maxMessages = 10)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].size)
    }

    @Test
    fun charLimitSplitsIntoMultipleChunks() {
        val messages = listOf(
            msg("a".repeat(60), tsMillis = 1_000),
            msg("b".repeat(60), tsMillis = 2_000),
            msg("c".repeat(60), tsMillis = 3_000)
        )
        val chunks = splitEpisodeArchiveChunks(messages, maxChars = 100, maxMessages = 10)
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun messageCountLimitSplitsIntoMultipleChunks() {
        val messages = (1..5).map { msg("x", tsMillis = it * 1_000L) }
        val chunks = splitEpisodeArchiveChunks(messages, maxChars = 10_000, maxMessages = 2)
        assertEquals(3, chunks.size)
        assertEquals(listOf(2, 2, 1), chunks.map { it.size })
    }

    @Test
    fun sameMillisecondGroupNeverSplitAcrossChunks() {
        // 3 条同毫秒消息 + 条数上限 2：允许整批超限以保持同 ts 组完整
        val sameTs = listOf(
            msg("one", tsMillis = 5_000),
            msg("two", tsMillis = 5_000),
            msg("three", tsMillis = 5_000)
        )
        val later = msg("later", tsMillis = 9_000)
        val chunks = splitEpisodeArchiveChunks(sameTs + later, maxChars = 10_000, maxMessages = 2)
        assertEquals(2, chunks.size)
        assertEquals(3, chunks[0].size) // 同 ts 组整体留在第一批
        assertEquals(listOf(later.id), chunks[1].map { it.id })
    }

    @Test
    fun sameMillisecondStraddlingCharLimitStaysTogether() {
        // 首条撑满字符上限，与它同毫秒的后继消息不得被切到下一批
        val first = msg("a".repeat(100), tsMillis = 5_000)
        val sameTsTail = msg("tail", tsMillis = 5_000)
        val nextSecond = msg("next", tsMillis = 6_000)
        val chunks = splitEpisodeArchiveChunks(listOf(first, sameTsTail, nextSecond), maxChars = 100, maxMessages = 10)
        assertEquals(2, chunks.size)
        assertEquals(listOf(first.id, sameTsTail.id), chunks[0].map { it.id })
        assertEquals(listOf(nextSecond.id), chunks[1].map { it.id })
    }

    @Test
    fun regularMessagesBatchIntoOneChunkWhenWithinLimits() {
        val messages = (1..10).map { msg("m$it", tsMillis = it * 1_000L) }
        val chunks = splitEpisodeArchiveChunks(messages, maxChars = 10_000, maxMessages = 60)
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
    }
}
