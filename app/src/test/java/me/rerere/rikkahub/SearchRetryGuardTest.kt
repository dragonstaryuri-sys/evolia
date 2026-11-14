package me.rerere.rikkahub

import me.rerere.rikkahub.data.ai.SearchRetryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRetryGuardTest {

    // ------------------------------------------------------------------
    // 空结果引导与终止
    // ------------------------------------------------------------------

    @Test
    fun `empty results below limit guide retry and keep searching allowed`() {
        val guard = SearchRetryGuard(maxConsecutiveEmptyResults = 5)
        repeat(4) { i ->
            assertFalse("search must not be blocked at empty count $i", guard.shouldBlockSearch())
            val message = guard.noteEmptyResult()
            assertTrue("message should guide retry", message.contains("更换关键词"))
            assertEquals(i + 1, guard.consecutiveEmptyResults)
        }
    }

    @Test
    fun `reaching limit switches message to stop guidance`() {
        val guard = SearchRetryGuard()
        repeat(4) { guard.noteEmptyResult() }
        // 第 5 次空结果：返回终止提示（此后不再执行搜索）
        val message = guard.noteEmptyResult()
        assertTrue(message.contains("终止搜索"))
        assertTrue(message.contains("搜不到相关信息"))
        assertEquals(5, guard.consecutiveEmptyResults)
        assertTrue(guard.shouldBlockSearch())
    }

    @Test
    fun `search beyond limit is blocked without executing`() {
        val guard = SearchRetryGuard()
        repeat(5) { guard.noteEmptyResult() }
        assertTrue(guard.shouldBlockSearch())
        // 第 6 次调用直接被拦截（返回终止提示），计数不再增长
        val blockedMessage = guard.blockedMessage()
        assertTrue(blockedMessage.contains("终止搜索"))
        assertEquals(5, guard.consecutiveEmptyResults)
        assertTrue(guard.shouldBlockSearch())
    }

    // ------------------------------------------------------------------
    // 连续性：有效结果重置计数
    // ------------------------------------------------------------------

    @Test
    fun `successful search resets consecutive empty count`() {
        val guard = SearchRetryGuard()
        repeat(3) { guard.noteEmptyResult() }
        assertEquals(3, guard.consecutiveEmptyResults)
        guard.noteResultsFound()
        assertEquals(0, guard.consecutiveEmptyResults)
        assertFalse(guard.shouldBlockSearch())
    }

    @Test
    fun `non-consecutive empty results never reach block`() {
        val guard = SearchRetryGuard()
        // 多轮「搜3次空 → 有结果 → 再搜3次空」：连续计数被打断，永不拦截
        repeat(10) {
            repeat(3) { guard.noteEmptyResult() }
            guard.noteResultsFound()
        }
        assertFalse(guard.shouldBlockSearch())
        // 最后一轮以有效结果收尾：连续计数归零
        assertEquals(0, guard.consecutiveEmptyResults)
    }

    // ------------------------------------------------------------------
    // 默认上限
    // ------------------------------------------------------------------

    @Test
    fun `default limit is 5`() {
        assertEquals(5, SearchRetryGuard.DEFAULT_MAX_CONSECUTIVE_EMPTY_RESULTS)
        val guard = SearchRetryGuard()
        repeat(4) { guard.noteEmptyResult() }
        assertFalse(guard.shouldBlockSearch())
        guard.noteEmptyResult()
        assertTrue(guard.shouldBlockSearch())
    }

    @Test
    fun `retry message contains remaining budget`() {
        val guard = SearchRetryGuard(maxConsecutiveEmptyResults = 5)
        guard.noteEmptyResult()
        val message = guard.noteEmptyResult()
        assertTrue(message.contains("2 次"))
    }
}
