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
}
