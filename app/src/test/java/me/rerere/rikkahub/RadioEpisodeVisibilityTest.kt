package me.rerere.rikkahub

import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeVisibility
import me.rerere.rikkahub.data.radio.RadioEpisodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节目可见性纯业务规则（DB v47，AGENTS.md 12.2）：
 * PRIVATE 节目仅创作 Agent 与 user 可查询，public 对所有 Agent 开放；
 * 可见性仅在发布时指定（publish_episode），已发布后可由 Agent 通过工具修改。
 */
class RadioEpisodeVisibilityTest {

    private val creator = "agent-a"
    private val other = "agent-b"

    // ==================== normalizeVisibility ====================

    @Test
    fun `normalize accepts public and private case-insensitively`() {
        assertEquals(RadioEpisodeVisibility.PUBLIC, RadioEpisodeRules.normalizeVisibility("public"))
        assertEquals(RadioEpisodeVisibility.PRIVATE, RadioEpisodeRules.normalizeVisibility("private"))
        assertEquals(RadioEpisodeVisibility.PUBLIC, RadioEpisodeRules.normalizeVisibility("PUBLIC"))
        assertEquals(RadioEpisodeVisibility.PRIVATE, RadioEpisodeRules.normalizeVisibility("Private"))
        // 规范化为大写常量落库
        assertEquals("PUBLIC", RadioEpisodeRules.normalizeVisibility("public"))
        assertEquals("PRIVATE", RadioEpisodeRules.normalizeVisibility("private"))
    }

    @Test
    fun `normalize trims whitespace`() {
        assertEquals(RadioEpisodeVisibility.PUBLIC, RadioEpisodeRules.normalizeVisibility(" public "))
        assertEquals(RadioEpisodeVisibility.PRIVATE, RadioEpisodeRules.normalizeVisibility("\tprivate\n"))
    }

    @Test
    fun `normalize rejects invalid or blank or null input`() {
        assertNull(RadioEpisodeRules.normalizeVisibility(null))
        assertNull(RadioEpisodeRules.normalizeVisibility(""))
        assertNull(RadioEpisodeRules.normalizeVisibility("  "))
        assertNull(RadioEpisodeRules.normalizeVisibility("friend"))
        assertNull(RadioEpisodeRules.normalizeVisibility("draft"))
    }

    // ==================== isVisibleToAgent ====================

    @Test
    fun `public episode visible to all agents`() {
        assertTrue(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = RadioEpisodeVisibility.PUBLIC,
                creatorAgentId = creator,
                viewerAgentId = other
            )
        )
    }

    @Test
    fun `private episode visible only to creator agent`() {
        assertTrue(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = RadioEpisodeVisibility.PRIVATE,
                creatorAgentId = creator,
                viewerAgentId = creator
            )
        )
        assertFalse(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = RadioEpisodeVisibility.PRIVATE,
                creatorAgentId = creator,
                viewerAgentId = other
            )
        )
    }

    @Test
    fun `null viewer means user context and sees everything`() {
        // user / UI 视角不做可见性过滤，private 节目也可见
        assertTrue(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = RadioEpisodeVisibility.PRIVATE,
                creatorAgentId = creator,
                viewerAgentId = null
            )
        )
        assertTrue(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = RadioEpisodeVisibility.PUBLIC,
                creatorAgentId = creator,
                viewerAgentId = null
            )
        )
    }

    @Test
    fun `unknown legacy visibility values treated as public`() {
        // 迁移兜底：历史数据默认 PUBLIC；意外值（如旧版本写入）不应把节目对 Agent 藏起来
        assertTrue(
            RadioEpisodeRules.isVisibleToAgent(
                visibility = "LEGACY",
                creatorAgentId = creator,
                viewerAgentId = other
            )
        )
    }

    // ==================== canModifyVisibility ====================

    @Test
    fun `visibility modifiable only for published episodes`() {
        // 草稿的可见性在 publish_episode 时指定，不单独修改
        assertFalse(RadioEpisodeRules.canModifyVisibility(RadioEpisodeStatus.DRAFT))
        assertTrue(RadioEpisodeRules.canModifyVisibility(RadioEpisodeStatus.PUBLISHED))
    }
}
