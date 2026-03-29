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

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE assistant_id = :assistantId ORDER BY scheduled_time ASC")
    fun getTasksByAssistant(assistantId: String): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE is_executed = 0 AND scheduled_time <= :currentTime")
    suspend fun getPendingTasks(currentTime: Long): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks ORDER BY scheduled_time ASC")
    fun getAllTasks(): Flow<List<AgentTaskEntity>>
}
