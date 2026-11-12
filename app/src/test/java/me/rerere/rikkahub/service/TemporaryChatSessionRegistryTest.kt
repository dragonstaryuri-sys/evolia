package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 临时会话注册表单元测试（纯 JVM，不依赖 Android API）。
 *
 * 覆盖验收标准 D/E 中的会话身份生命周期：
 * - 创建即注册（不等第一次发消息）
 * - 身份判定唯一权威（isTemporary / isDestroying）
 * - 销毁标记阻止新消息、移除后身份失效
 * - 销毁幂等与未知会话 no-op
 */
class TemporaryChatSessionRegistryTest {

    private val registry = TemporaryChatSessionRegistry()

    @Test
    fun `create 后会话立即被识别为临时会话`() {
        val sessionId = Uuid.random()
        val assistantId = Uuid.random()
        registry.create(sessionId, assistantId)

        assertTrue(registry.isTemporary(sessionId))
        assertFalse(registry.isDestroying(sessionId))
        assertNotNull(registry.get(sessionId))
        assertEquals(assistantId, registry.get(sessionId)?.assistantId)
    }

    @Test
    fun `未注册的会话不是临时会话`() {
        val randomId = Uuid.random()
        assertFalse(registry.isTemporary(randomId))
        assertFalse(registry.isDestroying(randomId))
        assertNull(registry.get(randomId))
    }

    @Test
    fun `markDestroying 后 isDestroying 为真且仍保留身份`() {
        val sessionId = Uuid.random()
        registry.create(sessionId, Uuid.random())
        registry.markDestroying(sessionId)

        assertTrue(registry.isDestroying(sessionId))
        // sendMessage 入口以 isDestroying 拒绝新消息，但身份在销毁完成前仍需可查
        assertTrue(registry.isTemporary(sessionId))
    }

    @Test
    fun `remove 后身份失效且 destroying 清除`() {
        val sessionId = Uuid.random()
        registry.create(sessionId, Uuid.random())
        registry.markDestroying(sessionId)

        val removed = registry.remove(sessionId)

        assertEquals(sessionId, removed?.sessionId)
        assertFalse(registry.isTemporary(sessionId))
        assertFalse(registry.isDestroying(sessionId))
    }

    @Test
    fun `重复 create 同一 id 覆盖旧会话（新会话重置 destroying）`() {
        val sessionId = Uuid.random()
        val firstAssistant = Uuid.random()
        registry.create(sessionId, firstAssistant)
        registry.markDestroying(sessionId)

        val secondAssistant = Uuid.random()
        registry.create(sessionId, secondAssistant)

        assertTrue(registry.isTemporary(sessionId))
        assertFalse(registry.isDestroying(sessionId))
        assertEquals(secondAssistant, registry.get(sessionId)?.assistantId)
    }

    @Test
    fun `markDestroying 对未知会话是 no-op`() {
        val randomId = Uuid.random()
        registry.markDestroying(randomId)
        assertFalse(registry.isDestroying(randomId))
    }

    @Test
    fun `remove 未知会话返回 null`() {
        assertNull(registry.remove(Uuid.random()))
    }

    @Test
    fun `activeSessionIds 只包含活跃会话`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        registry.create(id1, Uuid.random())
        registry.create(id2, Uuid.random())

        assertEquals(setOf(id1, id2), registry.activeSessionIds())

        registry.remove(id1)
        assertEquals(setOf(id2), registry.activeSessionIds())
    }
}
