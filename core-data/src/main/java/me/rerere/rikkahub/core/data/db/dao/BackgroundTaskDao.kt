package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity

/**
 * 通用后台任务 DAO。
 *
 * 所有状态变更一律使用条件 UPDATE（自带 SQLite 写锁，返回受影响行数作为唯一判据），
 * 与 AgentTaskDAO 的 claim-first 模式一致：
 * - 执行认领/终态/取消带 status（+execution_id 所有者）条件，终态不可被覆盖；
 * - 旧 Worker（execution_id 不匹配）无法写入新 Worker 的结果；
 * - 回调状态机各迁移互斥，防止重复投递。
 */
/**
 * ⚠️ SQL 状态字面量硬约束：实体列存 `BackgroundTaskStatus.name` / `BackgroundTaskCallbackState.name`
 * （全大写枚举名），SQLite 字符串比较默认 BINARY（大小写敏感）——
 * 所有 @Query 中的状态字面量必须写成 'QUEUED'/'RUNNING'/'PENDING' 等全大写，
 * 写成小写会导致条件 UPDATE 永远匹配 0 行（历史 bug：任务永远停在 QUEUED、Worker 重试耗尽）。
 */
@Dao
interface BackgroundTaskDao {

    @Insert
    suspend fun insertTask(task: BackgroundTaskEntity)

    @Query("SELECT * FROM background_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): BackgroundTaskEntity?

    @Query("SELECT * FROM background_tasks WHERE id = :taskId")
    fun observeTaskById(taskId: String): Flow<BackgroundTaskEntity?>

    /** 同业务引用键的活跃任务（冲突检查用，含 committing 防重复提交） */
    @Query(
        """
        SELECT * FROM background_tasks
        WHERE task_type = :taskType AND ref_key = :refKey
          AND status IN ('QUEUED', 'RUNNING', 'COMMITTING')
        ORDER BY created_at ASC
        """
    )
    suspend fun getActiveTasksByRef(taskType: String, refKey: String): List<BackgroundTaskEntity>

    /** 全部活跃任务（App 启动恢复扫描用） */
    @Query(
        """
        SELECT * FROM background_tasks
        WHERE status IN ('QUEUED', 'RUNNING', 'COMMITTING')
        ORDER BY created_at ASC
        """
    )
    suspend fun getAllActiveTasks(): List<BackgroundTaskEntity>

    /** 某会话最近的任务（get_background_task 无参查询用） */
    @Query(
        """
        SELECT * FROM background_tasks
        WHERE conversation_id = :conversationId
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentTasksByConversation(conversationId: String, limit: Int): List<BackgroundTaskEntity>

    // ============================================================
    // 执行租约状态机
    // ============================================================

    /**
     * 首次认领：queued → running，写入执行租约。
     * 返回 0 = 已被其他实例认领 / 已是终态 / 已取消。
     */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'RUNNING', execution_id = :executionId, lease_expires_at = :leaseUntil,
            started_at = COALESCE(started_at, :now)
        WHERE id = :taskId AND status = 'QUEUED'
        """
    )
    suspend fun claimTask(taskId: String, executionId: String, leaseUntil: Long, now: Long): Int

    /**
     * 失效重认领：running/committing 且租约过期 → running（换新执行者）。
     * 进程被杀 / 系统停止 Worker 后由 WorkManager 重跑实例调用；
     * committing 回退 running 由恢复流程按 task_id 检查资产提交状态后继续（见 RadioAudioGenerationExecutor）。
     */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'RUNNING', execution_id = :executionId, lease_expires_at = :leaseUntil
        WHERE id = :taskId
          AND status IN ('RUNNING', 'COMMITTING')
          AND (execution_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at < :now)
        """
    )
    suspend fun reclaimStaleTask(taskId: String, executionId: String, leaseUntil: Long, now: Long): Int

    /** 心跳续租 + 进度更新（仅当前租约所有者可执行） */
    @Query(
        """
        UPDATE background_tasks
        SET lease_expires_at = :leaseUntil, progress_json = :progressJson
        WHERE id = :taskId AND execution_id = :executionId AND status IN ('RUNNING', 'COMMITTING')
        """
    )
    suspend fun renewLease(taskId: String, executionId: String, leaseUntil: Long, progressJson: String?): Int

    /** running → committing（提交原子边界开启；此后取消被拒绝） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'COMMITTING', lease_expires_at = :leaseUntil
        WHERE id = :taskId AND status = 'RUNNING' AND execution_id = :executionId
        """
    )
    suspend fun toCommitting(taskId: String, executionId: String, leaseUntil: Long): Int

    /** committing → succeeded（结果与终态同一条 UPDATE 原子落库） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'SUCCEEDED', result_json = :resultJson, completed_at = :now
        WHERE id = :taskId AND status = 'COMMITTING' AND execution_id = :executionId
        """
    )
    suspend fun finalizeSucceeded(taskId: String, executionId: String, resultJson: String, now: Long): Int

    /** running/committing → failed（仅当前执行者；committing 提交失败也走这里） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'FAILED', error_message = :errorMessage, completed_at = :now
        WHERE id = :taskId AND status IN ('RUNNING', 'COMMITTING') AND execution_id = :executionId
        """
    )
    suspend fun finalizeFailed(taskId: String, executionId: String, errorMessage: String, now: Long): Int

    /** queued → failed（认领前 deadline 兜底检查用） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'FAILED', error_message = :errorMessage, completed_at = :now
        WHERE id = :taskId AND status = 'QUEUED'
        """
    )
    suspend fun failQueuedTask(taskId: String, errorMessage: String, now: Long): Int

    /** running → queued（本执行者主动重试回退，retry_count+1，清空租约） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'QUEUED', execution_id = NULL, lease_expires_at = NULL, retry_count = retry_count + 1
        WHERE id = :taskId AND status = 'RUNNING' AND execution_id = :executionId
        """
    )
    suspend fun backToQueued(taskId: String, executionId: String): Int

    /**
     * 恢复扫描：running/committing 且租约已过期 → queued（清空租约与执行者）。
     * 进程被杀后 WorkManager 丢失 work 时的启动恢复路径；不推进 retry_count（非执行者主动重试）。
     */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'QUEUED', execution_id = NULL, lease_expires_at = NULL
        WHERE id = :taskId AND status IN ('RUNNING', 'COMMITTING')
          AND (lease_expires_at IS NULL OR lease_expires_at < :now)
        """
    )
    suspend fun resetStaleToQueued(taskId: String, now: Long): Int

    /** 用户/工具取消：仅 queued/running 可取消；committing 及终态拒绝（返回 0） */
    @Query(
        """
        UPDATE background_tasks
        SET status = 'CANCELLED', completed_at = :now
        WHERE id = :taskId AND status IN ('QUEUED', 'RUNNING')
        """
    )
    suspend fun cancelTask(taskId: String, now: Long): Int

    // ============================================================
    // 回调（Agent 唤起）状态机
    // ============================================================

    /** 任务到达终态后打开回调待投递（幂等：仅 NONE 一次） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'PENDING'
        WHERE id = :taskId AND status IN ('SUCCEEDED', 'FAILED') AND callback_state = 'NONE'
        """
    )
    suspend fun markCallbackPending(taskId: String): Int

    /** 认领投递：PENDING → DELIVERING（写认领时间 + 尝试次数） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'DELIVERING', callback_claimed_at = :now, callback_attempt_count = callback_attempt_count + 1
        WHERE id = :taskId AND callback_state = 'PENDING'
        """
    )
    suspend fun claimCallback(taskId: String, now: Long): Int

    /** 注入完成：DELIVERING → INJECTED（回调消息已落库后调用） */
    @Query(
        "UPDATE background_tasks SET callback_state = 'INJECTED' WHERE id = :taskId AND callback_state = 'DELIVERING'"
    )
    suspend fun markCallbackInjected(taskId: String): Int

    /** 回调轮次结束：INJECTED/DELIVERING → COMPLETED（任何轮次出口，error 记录中断原因供诊断） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'COMPLETED', callback_last_error = :error
        WHERE id = :taskId AND callback_state IN ('INJECTED', 'DELIVERING')
        """
    )
    suspend fun markCallbackCompleted(taskId: String, error: String?): Int

    /** 目标不可达：PENDING/DELIVERING → ABANDONED（任务结果保留，兜底走通知） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'ABANDONED', callback_last_error = :error
        WHERE id = :taskId AND callback_state IN ('PENDING', 'DELIVERING')
        """
    )
    suspend fun markCallbackAbandoned(taskId: String, error: String): Int

    /** 单个任务 DELIVERING 租约过期回退 PENDING（恢复扫描；INJECTED 不回退——注入是持久事实） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'PENDING', callback_last_error = :error
        WHERE id = :taskId AND callback_state = 'DELIVERING'
          AND callback_claimed_at IS NOT NULL AND callback_claimed_at < :expiredBefore
        """
    )
    suspend fun resetStaleDelivering(taskId: String, expiredBefore: Long, error: String): Int

    /** 全部过期 DELIVERING 回退 PENDING（App 启动恢复扫描用） */
    @Query(
        """
        UPDATE background_tasks
        SET callback_state = 'PENDING'
        WHERE callback_state = 'DELIVERING'
          AND callback_claimed_at IS NOT NULL AND callback_claimed_at < :expiredBefore
        """
    )
    suspend fun resetAllStaleDeliverings(expiredBefore: Long): Int

    /** 待投递回调列表（投递扫描用；同会话按创建时间排序保证多任务回调有序） */
    @Query(
        """
        SELECT * FROM background_tasks
        WHERE callback_state = 'PENDING'
        ORDER BY created_at ASC
        """
    )
    suspend fun getPendingCallbackTasks(): List<BackgroundTaskEntity>

    /**
     * 已注入未完成的回调（App 启动恢复 / 生成结束钩子扫描用）：
     * 恢复决策基于会话内容（有响应→COMPLETED；无响应且末节点→续跑生成），见 BackgroundTaskRules。
     */
    @Query(
        """
        SELECT * FROM background_tasks
        WHERE callback_state = 'INJECTED'
        ORDER BY created_at ASC
        """
    )
    suspend fun getInjectedCallbackTasks(): List<BackgroundTaskEntity>
}
