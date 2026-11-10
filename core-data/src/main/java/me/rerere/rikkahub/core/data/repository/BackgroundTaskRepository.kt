package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.BackgroundTaskDao
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity

/**
 * 通用后台任务数据访问层（纯 DB 包装，不持有业务逻辑）。
 * 状态变更均透传 DAO 条件 UPDATE，返回受影响行数供调用方判定并发结果。
 */
class BackgroundTaskRepository(
    private val backgroundTaskDao: BackgroundTaskDao
) {
    suspend fun getTaskById(taskId: String): BackgroundTaskEntity? =
        backgroundTaskDao.getTaskById(taskId)

    fun observeTaskById(taskId: String): Flow<BackgroundTaskEntity?> =
        backgroundTaskDao.observeTaskById(taskId)

    suspend fun insertTask(task: BackgroundTaskEntity) = backgroundTaskDao.insertTask(task)

    suspend fun getActiveTasksByRef(taskType: String, refKey: String): List<BackgroundTaskEntity> =
        backgroundTaskDao.getActiveTasksByRef(taskType, refKey)

    suspend fun getAllActiveTasks(): List<BackgroundTaskEntity> =
        backgroundTaskDao.getAllActiveTasks()

    suspend fun getRecentTasksByConversation(conversationId: String, limit: Int): List<BackgroundTaskEntity> =
        backgroundTaskDao.getRecentTasksByConversation(conversationId, limit)

    // 执行状态机
    suspend fun claimTask(taskId: String, executionId: String, leaseUntil: Long, now: Long): Int =
        backgroundTaskDao.claimTask(taskId, executionId, leaseUntil, now)

    suspend fun reclaimStaleTask(taskId: String, executionId: String, leaseUntil: Long, now: Long): Int =
        backgroundTaskDao.reclaimStaleTask(taskId, executionId, leaseUntil, now)

    suspend fun renewLease(taskId: String, executionId: String, leaseUntil: Long, progressJson: String?): Int =
        backgroundTaskDao.renewLease(taskId, executionId, leaseUntil, progressJson)

    suspend fun toCommitting(taskId: String, executionId: String, leaseUntil: Long): Int =
        backgroundTaskDao.toCommitting(taskId, executionId, leaseUntil)

    suspend fun finalizeSucceeded(taskId: String, executionId: String, resultJson: String, now: Long): Int =
        backgroundTaskDao.finalizeSucceeded(taskId, executionId, resultJson, now)

    suspend fun finalizeFailed(taskId: String, executionId: String, errorMessage: String, now: Long): Int =
        backgroundTaskDao.finalizeFailed(taskId, executionId, errorMessage, now)

    suspend fun failQueuedTask(taskId: String, errorMessage: String, now: Long): Int =
        backgroundTaskDao.failQueuedTask(taskId, errorMessage, now)

    suspend fun backToQueued(taskId: String, executionId: String): Int =
        backgroundTaskDao.backToQueued(taskId, executionId)

    suspend fun resetStaleToQueued(taskId: String, now: Long): Int =
        backgroundTaskDao.resetStaleToQueued(taskId, now)

    suspend fun cancelTask(taskId: String, now: Long): Int =
        backgroundTaskDao.cancelTask(taskId, now)

    // 回调状态机
    suspend fun markCallbackPending(taskId: String): Int =
        backgroundTaskDao.markCallbackPending(taskId)

    suspend fun claimCallback(taskId: String, now: Long): Int =
        backgroundTaskDao.claimCallback(taskId, now)

    suspend fun markCallbackInjected(taskId: String): Int =
        backgroundTaskDao.markCallbackInjected(taskId)

    suspend fun markCallbackCompleted(taskId: String, error: String?): Int =
        backgroundTaskDao.markCallbackCompleted(taskId, error)

    suspend fun markCallbackAbandoned(taskId: String, error: String): Int =
        backgroundTaskDao.markCallbackAbandoned(taskId, error)

    suspend fun resetStaleDelivering(taskId: String, expiredBefore: Long, error: String): Int =
        backgroundTaskDao.resetStaleDelivering(taskId, expiredBefore, error)

    suspend fun resetAllStaleDeliverings(expiredBefore: Long): Int =
        backgroundTaskDao.resetAllStaleDeliverings(expiredBefore)

    suspend fun getPendingCallbackTasks(): List<BackgroundTaskEntity> =
        backgroundTaskDao.getPendingCallbackTasks()

    suspend fun getInjectedCallbackTasks(): List<BackgroundTaskEntity> =
        backgroundTaskDao.getInjectedCallbackTasks()
}
