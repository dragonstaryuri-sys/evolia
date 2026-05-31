package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity

@Dao
interface AgentMonitorTaskDAO {
    @Query("SELECT * FROM agent_monitor_tasks WHERE assistant_id = :assistantId")
    fun getTasksByAssistant(assistantId: String): Flow<List<AgentMonitorTaskEntity>>

    @Query("SELECT * FROM agent_monitor_tasks WHERE is_enabled = 1")
    fun getAllEnabledTasks(): Flow<List<AgentMonitorTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentMonitorTaskEntity): Long

    @Update
    suspend fun updateTask(task: AgentMonitorTaskEntity)

    @Delete
    suspend fun deleteTask(task: AgentMonitorTaskEntity)

    @Query("DELETE FROM agent_monitor_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("SELECT * FROM agent_monitor_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): AgentMonitorTaskEntity?
}
