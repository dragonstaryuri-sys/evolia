package me.rerere.rikkahub

import me.rerere.rikkahub.data.sync.DatabaseSanitizer.isWhitelistedTable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份恢复白名单匹配规则验证。
 *
 * 背景：恢复时 DatabaseSanitizer 只把白名单内的表从备份库拷入新库，
 * 漏登记的表会静默丢失（曾导致 call_records 通话记录恢复后消失）。
 */
class DatabaseSanitizerWhitelistTest {

    // 与 DatabaseSanitizer.sanitize 中的白名单保持同步
    private val whiteList = listOf(
        "ConversationEntity", "MemoryEntity", "GenMediaEntity",
        "ChatEpisodeEntity", "EmbeddingCacheEntity", "embedding_cache",
        "daily_activity", "AgentDiaryEntity", "DiaryCommentEntity", "schedules", "chat_segments",
        "agent_tasks", "token_usage", "books", "book_progress",
        "MilestoneEntity", "user_device_state", "agent_monitor_tasks",
        "assistant_extended_state", "favorites", "profile_history",
        "chat_messages", "chat_message_nodes", "app_locks",
        "schedule_events", "call_records"
    )

    @Test
    fun `call_records is whitelisted by physical table name`() {
        assertTrue(isWhitelistedTable(whiteList, "call_records"))
    }

    @Test
    fun `snake_case entries match case-insensitively`() {
        assertTrue(isWhitelistedTable(whiteList, "CALL_RECORDS"))
        assertTrue(isWhitelistedTable(whiteList, "Schedules"))
        assertTrue(isWhitelistedTable(whiteList, "schedule_events"))
    }

    @Test
    fun `camelCase class name entries match their default table name`() {
        // Room 默认表名 = 类名，白名单允许直接写类名
        assertTrue(isWhitelistedTable(whiteList, "ConversationEntity"))
        assertTrue(isWhitelistedTable(whiteList, "MemoryEntity"))
    }

    @Test
    fun `camelCase class name does NOT match snake_case table name`() {
        // 已知的匹配规则限制：类名规范化为 "callrecordentity"，
        // 与蛇形表名 "call_records" 的 "callrecords" 不相等——
        // 因此白名单必须登记蛇形物理表名，这也是 call_records 曾被漏掉的坑。
        // 注意 "callrecord"（类名去 entity 后缀）反而能匹配，那是 entity 后缀规则的设计行为
        assertFalse(isWhitelistedTable(listOf("CallRecordEntity"), "call_records"))
        assertTrue(isWhitelistedTable(listOf("CallRecordEntity"), "callrecord"))
    }

    @Test
    fun `non whitelisted table is rejected`() {
        assertFalse(isWhitelistedTable(whiteList, "room_master_table"))
        assertFalse(isWhitelistedTable(whiteList, "sqlite_sequence"))
        assertFalse(isWhitelistedTable(whiteList, "some_future_table"))
    }

    @Test
    fun `every database table name in whitelist matches itself`() {
        // 白名单中的蛇形物理表名条目必须能匹配自身，防止手滑写错表名
        val snakeCaseEntries = whiteList.filter { it.contains('_') || it == it.lowercase() }
        assertTrue(snakeCaseEntries.isNotEmpty())
        snakeCaseEntries.forEach { entry ->
            assertTrue("白名单条目 $entry 应匹配同名物理表", isWhitelistedTable(whiteList, entry))
        }
    }
}
