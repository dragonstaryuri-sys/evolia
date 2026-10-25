package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AgentTaskDAO
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity

/**
 * 定时任务认领结果。
 * @property claimed 是否认领成功；false 表示任务已被其他并发实例接管，调用方应直接退出
 * @property nextTask 认领成功且为重复任务时，事务内已创建好的下一周期任务（数据库已插入，仍需调用方调度）
 */
data class ClaimedTask(
    val claimed: Boolean,
    val nextTask: AgentTaskEntity?
)

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

    suspend fun deleteExecutedTasks() = agentTaskDAO.deleteExecutedTasks()

    suspend fun getTaskById(id: Long): AgentTaskEntity? = agentTaskDAO.getTaskById(id)

    suspend fun claimAndChainNext(task: AgentTaskEntity, nextScheduledTime: Long?): ClaimedTask {
        val (claimed, nextTask) = agentTaskDAO.claimAndChainNext(task, nextScheduledTime)
        return ClaimedTask(claimed = claimed, nextTask = nextTask)
    }
}
