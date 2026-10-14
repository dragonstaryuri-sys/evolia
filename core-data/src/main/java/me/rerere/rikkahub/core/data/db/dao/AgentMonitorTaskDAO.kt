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

    /**
     * 仅更新 pause_until 字段。用于 peek_user 的 pause 动作，
     * 避免覆盖 monitor_name/conditions/actions 等其他字段。
     */
    @Query("UPDATE agent_monitor_tasks SET pause_until = :pauseUntil WHERE id = :id")
    suspend fun updatePauseUntil(id: Long, pauseUntil: Long)
}
