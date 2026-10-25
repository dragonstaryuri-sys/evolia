package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity

@Dao
interface AgentTaskDAO {
    @Insert
    suspend fun insertTask(task: AgentTaskEntity): Long

    @Update
    suspend fun updateTask(task: AgentTaskEntity)

    @Delete
    suspend fun deleteTask(task: AgentTaskEntity)

    @Query("DELETE FROM agent_tasks WHERE is_executed = 1")
    suspend fun deleteExecutedTasks()

    /**
     * 原子认领：仅当任务尚未执行时标记为已执行。
     * 返回受影响行数（1 = 认领成功，0 = 已被其他并发实例认领）。
     * 条件 UPDATE 自带写锁，是多 Worker 并发去重的唯一判据。
     */
    @Query("UPDATE agent_tasks SET is_executed = 1 WHERE id = :id AND is_executed = 0")
    suspend fun markTaskExecuted(id: Long): Int

    /**
     * 认领 + 续接放进同一事务：要么「标记已执行 && 创建下一周期记录」同时提交，
     * 要么全部回滚，避免进程在两步之间被杀导致重复链断裂。
     * 认领失败（claimed=false）说明另一并发实例已接管，调用方必须直接退出。
     */
    @Transaction
    suspend fun claimAndChainNext(
        task: AgentTaskEntity,
        nextScheduledTime: Long?
    ): Pair<Boolean, AgentTaskEntity?> {
        val claimed = markTaskExecuted(task.id) > 0
        if (!claimed) {
            return false to null
        }

        // 非重复任务（repeatInterval = 0）没有下一周期
        if (nextScheduledTime == null) {
            return true to null
        }

        val nextTask = task.copy(
            id = 0, // 创建新记录
            scheduledTime = nextScheduledTime,
            isExecuted = false,
            createdAt = System.currentTimeMillis()
        )
        val newId = insertTask(nextTask)
        return true to nextTask.copy(id = newId)
    }

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE assistant_id = :assistantId ORDER BY scheduled_time ASC")
    fun getTasksByAssistant(assistantId: String): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE is_executed = 0 AND scheduled_time <= :currentTime")
    suspend fun getPendingTasks(currentTime: Long): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks ORDER BY scheduled_time ASC")
    fun getAllTasks(): Flow<List<AgentTaskEntity>>
}
