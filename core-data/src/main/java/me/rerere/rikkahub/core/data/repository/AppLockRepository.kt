package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AppLockDAO
import me.rerere.rikkahub.core.data.db.entity.AppLockEntity

class AppLockRepository(private val appLockDao: AppLockDAO) {

    suspend fun upsertLock(lock: AppLockEntity): Long = appLockDao.upsertLock(lock)

    suspend fun unlockByPackageName(packageName: String) = appLockDao.deleteByPackageName(packageName)

    suspend fun getByPackageName(packageName: String): AppLockEntity? =
        appLockDao.getByPackageName(packageName)

    fun getAllLocks(): Flow<List<AppLockEntity>> = appLockDao.getAllLocks()

    suspend fun getAllLocksOnce(): List<AppLockEntity> = appLockDao.getAllLocksOnce()

    /**
     * 临时暂停某应用的锁 N 分钟。返回 pauseUntil 时间戳（毫秒），
     * 若应用未被锁定则返回 null。暂停期间 isLockedNow 返回 false，
     * 到期后自动恢复，无需手动清零。
     */
    suspend fun pauseLock(packageName: String, durationMinutes: Int): Long? {
        val existing = appLockDao.getByPackageName(packageName) ?: return null
        val pauseUntil = System.currentTimeMillis() + durationMinutes * 60_000L
        appLockDao.updatePauseUntil(packageName, pauseUntil)
        return pauseUntil
    }

    /**
     * 仅更新某应用的 valid_time 字段（用于 APP_LOCK_UPDATE_TIME 命令），
     * 不动其他字段。传 null 表示清除时间窗口。
     * 返回 false 表示该应用未被锁定（无记录可更新）。
     */
    suspend fun updateValidTime(packageName: String, validTime: String?): Boolean {
        appLockDao.getByPackageName(packageName) ?: return false
        appLockDao.updateValidTime(packageName, validTime)
        return true
    }
}
