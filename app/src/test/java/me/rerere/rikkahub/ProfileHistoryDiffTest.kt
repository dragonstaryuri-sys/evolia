package me.rerere.rikkahub

import me.rerere.rikkahub.core.data.db.entity.AssistantExtendedStateEntity
import me.rerere.rikkahub.core.data.repository.ProfileHistoryRepository
import me.rerere.rikkahub.data.datastore.UserProfile
import me.rerere.rikkahub.ui.pages.assistant.detail.toHistoryFieldMap as assistantFieldMap
import me.rerere.rikkahub.ui.pages.setting.toHistoryFieldMap as userFieldMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档案历史版本记录规则：
 * 1. 无论旧值是 AI 写的还是用户手写的，覆盖更新前都要把被改字段的旧值写入历史；
 * 2. 手动保存（用户/助手档案）与 AI update_profile 工具使用同一套 fieldKey，
 *    UI 的 historyByField 才能把历史渲染到对应字段下方。
 */
class ProfileHistoryDiffTest {

    private val userToolKeys = setOf(
        "birthday", "appearance", "occupation", "preferences", "diet",
        "health", "taboos", "interaction_preferences", "important_relationships"
    )

    private val assistantToolKeys = setOf(
        "personality", "appearance", "preferences", "diet",
        "taboos", "interaction_habits", "relationships"
    )

    // ── diffFields 纯函数 ──────────────────────────────────────────────

    @Test
    fun `diff 无变化时返回空`() {
        val old = mapOf("preferences" to "喜欢猫", "diet" to "不吃辣")
        val new = mapOf("preferences" to "喜欢猫", "diet" to "不吃辣")
        assertTrue(ProfileHistoryRepository.diffFields(old, new).isEmpty())
    }

    @Test
    fun `diff 单字段变化只记录该字段`() {
        val old = mapOf("preferences" to "喜欢猫", "diet" to "不吃辣")
        val new = mapOf("preferences" to "喜欢狗", "diet" to "不吃辣")
        val changes = ProfileHistoryRepository.diffFields(old, new)
        assertEquals(mapOf("preferences" to ("喜欢猫" to "喜欢狗")), changes)
    }

    @Test
    fun `diff 多字段变化全部记录`() {
        val old = mapOf("preferences" to "A", "diet" to "B", "health" to "C")
        val new = mapOf("preferences" to "A2", "diet" to "B2", "health" to "C")
        val changes = ProfileHistoryRepository.diffFields(old, new)
        assertEquals(2, changes.size)
        assertEquals("A" to "A2", changes["preferences"])
        assertEquals("B" to "B2", changes["diet"])
    }

    @Test
    fun `diff 空旧值被覆盖时记录空字符串旧值`() {
        // 首次填写：旧值为默认空串，也应留历史（与 AI 工具路径一致）
        val old = mapOf("occupation" to "")
        val new = mapOf("occupation" to "工程师")
        val changes = ProfileHistoryRepository.diffFields(old, new)
        assertEquals("" to "工程师", changes["occupation"])
    }

    @Test
    fun `diff 清空字段时记录被清掉的旧值`() {
        val old = mapOf("taboos" to "不吃香菜")
        val new = mapOf("taboos" to "")
        val changes = ProfileHistoryRepository.diffFields(old, new)
        assertEquals("不吃香菜" to "", changes["taboos"])
    }

    @Test
    fun `diff 仅存在于新值映射的键被忽略`() {
        // 防御性：调用方应保证两侧键一致，缺键时忽略而不是崩溃或误记 null
        val old = mapOf("preferences" to "A")
        val new = mapOf("preferences" to "A", "extra" to "B")
        assertTrue(ProfileHistoryRepository.diffFields(old, new).isEmpty())
    }

    // ── fieldKey 与 AI update_profile 工具保持一致 ─────────────────────

    @Test
    fun `用户档案字段映射键与 AI 工具键一致`() {
        val map = UserProfile().userFieldMap()
        assertEquals(userToolKeys, map.keys)
        // 默认档案全部为空串，保证首次保存也能正确 diff
        assertTrue(map.values.all { it.isEmpty() })
    }

    @Test
    fun `助手档案字段映射键与 AI 工具键一致`() {
        val map = AssistantExtendedStateEntity(assistantId = "a1").assistantFieldMap()
        assertEquals(assistantToolKeys, map.keys)
        assertTrue(map.values.all { it.isEmpty() })
    }

    @Test
    fun `用户档案手动修改可通过映射检出变化`() {
        val oldProfile = UserProfile(appearance = "短发", preferences = "安静")
        val newProfile = oldProfile.copy(preferences = "热闹", occupation = "教师")
        val changes = ProfileHistoryRepository.diffFields(
            oldValues = oldProfile.userFieldMap(),
            newValues = newProfile.userFieldMap()
        )
        assertEquals(
            mapOf(
                "preferences" to ("安静" to "热闹"),
                "occupation" to ("" to "教师")
            ),
            changes
        )
    }

    @Test
    fun `助手档案手动修改可通过映射检出变化`() {
        val oldState = AssistantExtendedStateEntity(
            assistantId = "a1",
            personality = "温柔",
            interactionHabits = "喜欢晚安电话"
        )
        val newState = oldState.copy(personality = "活泼")
        val changes = ProfileHistoryRepository.diffFields(
            oldValues = oldState.assistantFieldMap(),
            newValues = newState.assistantFieldMap()
        )
        assertEquals(mapOf("personality" to ("温柔" to "活泼")), changes)
    }
}
