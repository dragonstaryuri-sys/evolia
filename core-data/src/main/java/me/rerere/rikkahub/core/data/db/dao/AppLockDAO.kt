package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AppLockEntity

@Dao
interface AppLockDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLock(lock: AppLockEntity): Long

    @Query("DELETE FROM app_locks WHERE package_name = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("SELECT * FROM app_locks WHERE package_name = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): AppLockEntity?

    @Query("SELECT * FROM app_locks ORDER BY created_at DESC")
    fun getAllLocks(): Flow<List<AppLockEntity>>

    @Query("SELECT * FROM app_locks ORDER BY created_at DESC")
    suspend fun getAllLocksOnce(): List<AppLockEntity>

    /**
     * 仅更新 pause_until 字段。用于 APP_LOCK_PAUSE 命令，
     * 避免覆盖 prompt_message/emoji/遮罩图等其他字段。
     */
    @Query("UPDATE app_locks SET pause_until = :pauseUntil WHERE package_name = :packageName")
    suspend fun updatePauseUntil(packageName: String, pauseUntil: Long)

    /**
     * 仅更新 valid_time 字段。用于 APP_LOCK_UPDATE_TIME 命令，
     * 避免覆盖其他字段。传 null 表示清除时间窗口。
     */
    @Query("UPDATE app_locks SET valid_time = :validTime WHERE package_name = :packageName")
    suspend fun updateValidTime(packageName: String, validTime: String?)
}
