package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AgentMonitorTaskDAO
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity

class AgentMonitorTaskRepository(private val agentMonitorTaskDAO: AgentMonitorTaskDAO) {
    fun getTasksByAssistant(assistantId: String): Flow<List<AgentMonitorTaskEntity>> =
        agentMonitorTaskDAO.getTasksByAssistant(assistantId)

    fun getAllEnabledTasks(): Flow<List<AgentMonitorTaskEntity>> =
        agentMonitorTaskDAO.getAllEnabledTasks()

    suspend fun addTask(task: AgentMonitorTaskEntity): Long =
        agentMonitorTaskDAO.insertTask(task)

    suspend fun updateTask(task: AgentMonitorTaskEntity) =
        agentMonitorTaskDAO.updateTask(task)

    suspend fun deleteTask(task: AgentMonitorTaskEntity) =
        agentMonitorTaskDAO.deleteTask(task)

    suspend fun deleteTaskById(id: Long) =
        agentMonitorTaskDAO.deleteTaskById(id)

    suspend fun getTaskById(id: Long): AgentMonitorTaskEntity? =
        agentMonitorTaskDAO.getTaskById(id)

    /**
     * 临时暂停某个监视器 N 分钟。返回 pauseUntil 时间戳（毫秒），
     * 若监视器不存在则返回 null。暂停期间该监视器不会被评估触发，
     * 到期后自动恢复，无需手动清零。
     */
    suspend fun pauseMonitor(id: Long, durationMinutes: Int): Long? {
        val existing = agentMonitorTaskDAO.getTaskById(id) ?: return null
        val pauseUntil = System.currentTimeMillis() + durationMinutes * 60_000L
        agentMonitorTaskDAO.updatePauseUntil(id, pauseUntil)
        return pauseUntil
    }
}
