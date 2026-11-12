package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 临时会话注册表：临时会话身份的唯一权威来源。
 *
 * 设计约束（临时会话独立化改造，见 agents.md）：
 * - 临时会话 ID 是运行期 UUID，绝不写入 Conversation / ChatMessage 等正式数据库表。
 * - 会话在【创建时】即注册（不等第一次发消息），发送、生成、总结、记忆等链路统一通过
 *   [isTemporary] / [isDestroying] 查询模式，不依赖调用方逐个传参。
 * - 纯内存、纯类，无 Android 依赖，可单元测试；进程死亡即自然销毁，无需持久化恢复。
 */
class TemporaryChatSessionRegistry {

    data class TemporaryChatSession(
        val sessionId: Uuid,
        val assistantId: Uuid,
        val createdAt: Long,
    )

    private val sessions = ConcurrentHashMap<Uuid, TemporaryChatSession>()

    /** 正在销毁中的会话：阻止新消息进入，销毁流程完成后从 [sessions] 移除 */
    private val destroying = ConcurrentHashMap.newKeySet<Uuid>()

    /**
     * 注册一个临时会话。sessionId 由调用方（ChatService）生成，
     * 保证「创建内存会话 → 注册身份」在同一处完成。
     */
    fun create(sessionId: Uuid, assistantId: Uuid, createdAt: Long = System.currentTimeMillis()) {
        sessions[sessionId] = TemporaryChatSession(
            sessionId = sessionId,
            assistantId = assistantId,
            createdAt = createdAt,
        )
        destroying.remove(sessionId)
    }

    fun get(id: Uuid): TemporaryChatSession? = sessions[id]

    fun isTemporary(id: Uuid): Boolean = sessions.containsKey(id)

    fun isDestroying(id: Uuid): Boolean = destroying.contains(id)

    /** 标记销毁中：此后 sendMessage 等入口必须拒绝为该会话写入新内容 */
    fun markDestroying(id: Uuid) {
        if (sessions.containsKey(id)) {
            destroying.add(id)
        }
    }

    /** 注销临时会话（销毁流程最后一步），返回被移除的会话（不存在则 null） */
    fun remove(id: Uuid): TemporaryChatSession? {
        destroying.remove(id)
        return sessions.remove(id)
    }

    /** 当前活跃（未销毁）的临时会话 ID 集合，供诊断/兜底清理使用 */
    fun activeSessionIds(): Set<Uuid> = sessions.keys.toSet()
}
