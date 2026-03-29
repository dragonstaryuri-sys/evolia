package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AgentTaskDAO
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity

class AgentTaskRepository(
    private val agentTaskDAO: AgentTaskDAO
) {
    fun getTasksByAssistant(assistantId: String): Flow<List<AgentTaskEntity>> =
        agentTaskDAO.getTasksByAssistant(assistantId)

    fun getAllTasks(): Flow<List<AgentTaskEntity>> = agentTaskDAO.getAllTasks()

    suspend fun getPendingTasks(currentTime: Long): List<AgentTaskEntity> =
        agentTaskDAO.getPendingTasks(currentTime)

    suspend fun addTask(task: AgentTaskEntity): Long = agentTaskDAO.insertTask(task)

    suspend fun updateTask(task: AgentTaskEntity) = agentTaskDAO.updateTask(task)

    suspend fun deleteTask(task: AgentTaskEntity) = agentTaskDAO.deleteTask(task)

    suspend fun getTaskById(id: Long): AgentTaskEntity? = agentTaskDAO.getTaskById(id)
}
