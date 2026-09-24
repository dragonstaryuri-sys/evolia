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
}
