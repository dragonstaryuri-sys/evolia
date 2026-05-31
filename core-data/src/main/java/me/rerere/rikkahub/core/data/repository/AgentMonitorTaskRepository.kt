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
}
