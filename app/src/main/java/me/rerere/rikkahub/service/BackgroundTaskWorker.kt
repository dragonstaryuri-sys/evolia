package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskStatus
import me.rerere.rikkahub.core.data.repository.BackgroundTaskRepository
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskTypes
import me.rerere.rikkahub.data.backgroundtask.ExecutionResult
import me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress
import me.rerere.rikkahub.data.backgroundtask.TaskDeadlineExceededException
import me.rerere.rikkahub.data.backgroundtask.TaskLeaseLostException
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 通用后台任务执行 Worker（WorkManager 长时运行 Worker + dataSync 前台类型，
 * 见 AGENTS.md 通用后台任务章节的执行机制分析与平台限制）。
 *
 * 职责：原子认领（含失效重认领）→ 前台保活通知 → 委派 Executor 业务执行（含逐句租约续期与
 * deadline 检查）→ 终态落库（succeeded/failed）→ 重试回退 → 回调置 PENDING 并触发投递。
 * 状态机的原子性全部由 BackgroundTaskDao 条件 UPDATE 保证。
 */
class BackgroundTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val backgroundTaskRepository: BackgroundTaskRepository by inject()
    private val backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        return try {
            runTask(taskId)
        } catch (e: CancellationException) {
            // Worker 被停止（用户取消 / 系统停止 / WorkManager 调度取消）：
            // 清理必须在 NonCancellable 内完成（取消的协程内挂起语句会立即失效，历史 bug 模式）
            onWorkerStopped(taskId)
            throw e
        } catch (e: TaskDeadlineExceededException) {
            Log.w(TAG, "task=$taskId deadline exceeded")
            failOwnedTask(taskId, e.message ?: TIMEOUT_MESSAGE, cleanup = true)
            Result.success()
        } catch (e: TaskLeaseLostException) {
            // 租约被其他实例接管：本实例静默退出，不触碰任务状态
            Log.w(TAG, "task=$taskId lease lost, exiting silently")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "task=$taskId unexpected failure", e)
            failOwnedTask(taskId, "执行异常：${e.message ?: e.javaClass.simpleName}", cleanup = true)
            Result.success()
        }
    }

    private suspend fun runTask(taskId: String): Result {
        val task = backgroundTaskRepository.getTaskById(taskId) ?: return Result.success()
        val status = BackgroundTaskStatus.from(task.status)
        if (status == null || status.isTerminal) return Result.success()
        val now = System.currentTimeMillis()

        // 排队等待期间的 deadline 兜底（deadline = created_at + 50min，含排队等待时间）
        if (status == BackgroundTaskStatus.QUEUED &&
            BackgroundTaskRules.isDeadlineExceeded(task.deadlineAt, now)
        ) {
            backgroundTaskRepository.failQueuedTask(taskId, TIMEOUT_MESSAGE, now)
            onTaskFinalized(taskId, task.completionPolicy)
            return Result.success()
        }

        // 原子认领：首次 queued→running；失效重认领（进程被杀后 WorkManager 重跑）
        val executionId = kotlin.uuid.Uuid.random().toString()
        val leaseUntil = now + BackgroundTaskRules.EXECUTION_LEASE_MS
        Log.i(TAG, "task=$taskId worker start status=${task.status} attempt=$runAttemptCount")
        val claimed = backgroundTaskRepository.claimTask(taskId, executionId, leaseUntil, now) > 0 ||
            backgroundTaskRepository.reclaimStaleTask(taskId, executionId, leaseUntil, now) > 0
        if (!claimed) {
            // 认领失败：任务非 queued（其他实例持有效租约 / 状态已被并发路径改变）。
            // 稍后重试（WorkManager backoff）；超限放弃（任务由启动恢复链兜底）。
            Log.w(TAG, "task=$taskId claim failed (db status=${task.status}), attempt=$runAttemptCount")
            return if (runAttemptCount <= CLAIM_CONFLICT_MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        Log.i(TAG, "task=$taskId claimed by execution=$executionId (attempt=$runAttemptCount, was=${task.status})")

        val executor = backgroundTaskManager.executorFor(task.taskType)
        if (executor == null) {
            failOwnedTask(taskId, "未知任务类型：${task.taskType}", cleanup = false)
            return Result.success()
        }

        setForeground(buildForegroundInfo(task, applicationContext.getString(R.string.notification_channel_background_task)))

        val result = executor.execute(task, executionId) { progressJson ->
            // 逐句进度回调：续租约 + deadline 检查 + 前台通知刷新
            val ts = System.currentTimeMillis()
            val renewed = backgroundTaskRepository.renewLease(
                taskId, executionId, ts + BackgroundTaskRules.EXECUTION_LEASE_MS, progressJson
            ) > 0
            if (!renewed) throw TaskLeaseLostException()
            if (BackgroundTaskRules.isDeadlineExceeded(task.deadlineAt, ts)) throw TaskDeadlineExceededException()
            updateForegroundNotification(task, progressJson)
        }

        val finishedAt = System.currentTimeMillis()
        // 失败判定使用最新任务行（retryCount 在执行期间可能已被推进）
        val currentTask = backgroundTaskRepository.getTaskById(taskId) ?: task
        when (result) {
            is ExecutionResult.Success -> {
                val rows = backgroundTaskRepository.finalizeSucceeded(taskId, executionId, result.resultJson, finishedAt)
                if (rows == 0) {
                    // 终态已被并发路径占用（防御）：保守清理本次产物
                    Log.w(TAG, "task=$taskId finalize succeeded rows=0, cleaning up output")
                    runCatching { executor.cleanup(currentTask) }
                }
            }

            is ExecutionResult.RetryableFailure -> {
                val canRetry = currentTask.retryCount < BackgroundTaskRules.MAX_TASK_RETRIES &&
                    !BackgroundTaskRules.isDeadlineExceeded(currentTask.deadlineAt, finishedAt)
                val backed = canRetry && backgroundTaskRepository.backToQueued(taskId, executionId) > 0
                if (backed) {
                    // 保留 chunk 断点续传数据，仅清理半成品最终文件；WorkManager 按线性退避重跑
                    runCatching { executor.cleanupTransientOutput(currentTask) }
                    Log.i(TAG, "task=$taskId retryable failure, back to queued: ${result.message}")
                    return Result.retry()
                }
                val reason = if (!canRetry) {
                    "${result.message}（已达重试上限或超过任务总时限）"
                } else result.message
                backgroundTaskRepository.finalizeFailed(taskId, executionId, reason, finishedAt)
                runCatching { executor.cleanup(currentTask) }
            }

            is ExecutionResult.PermanentFailure -> {
                backgroundTaskRepository.finalizeFailed(taskId, executionId, result.message, finishedAt)
                runCatching { executor.cleanup(currentTask) }
            }
        }

        onTaskFinalized(taskId, task.completionPolicy)
        return Result.success()
    }

    /** 终态后置：打开回调待投递并触发投递；notify_user 策略发兜底通知。 */
    private suspend fun onTaskFinalized(taskId: String, completionPolicy: String) {
        backgroundTaskRepository.markCallbackPending(taskId)
        backgroundTaskManager.dispatchCallbacksForTask(taskId)
        if (completionPolicy == me.rerere.rikkahub.data.backgroundtask.CompletionPolicies.NOTIFY_USER) {
            backgroundTaskRepository.getTaskById(taskId)?.let {
                backgroundTaskManager.sendResultNotification(it)
            }
        }
    }

    /** 未知异常兜底：仅当仍持有租约时置 failed（含产物清理）。 */
    private suspend fun failOwnedTask(taskId: String, message: String, cleanup: Boolean) {
        val task = backgroundTaskRepository.getTaskById(taskId) ?: return
        val executionId = task.executionId ?: return
        val finalized = backgroundTaskRepository.finalizeFailed(
            taskId, executionId, message, System.currentTimeMillis()
        ) > 0
        if (finalized) {
            if (cleanup) {
                backgroundTaskManager.executorFor(task.taskType)?.let { executor ->
                    runCatching { executor.cleanup(task) }
                }
            }
            onTaskFinalized(taskId, task.completionPolicy)
        }
    }

    /**
     * Worker 被停止：按 DB 状态区分「用户取消」与「系统停止」（NonCancellable，取消的
     * 协程内挂起语句会立即失效，历史 bug 模式）。
     * - 用户取消（Manager.cancelTask 先落 CANCELLED 终态再取消 Work）：清理产物 + 撤通知；
     * - 系统停止（网络约束变化 / dataSync onTimeout / 系统停 FGS）：保留状态与 chunk 断点
     *   续传数据，WorkManager 按退避重跑后由租约过期重认领续传；不置 CANCELLED、不删产物。
     */
    private suspend fun onWorkerStopped(taskId: String) {
        withContext(NonCancellable) {
            val task = backgroundTaskRepository.getTaskById(taskId)
            if (task != null) {
                val action = BackgroundTaskRules.decideWorkerStopAction(task.status)
                when (action) {
                    BackgroundTaskRules.WorkerStopAction.CLEANUP_CANCELLED -> {
                        backgroundTaskManager.executorFor(task.taskType)?.let { executor ->
                            runCatching { executor.cleanup(task) }
                        }
                        Log.i(TAG, "worker stopped: task=$taskId user-cancelled, output cleaned")
                    }

                    BackgroundTaskRules.WorkerStopAction.KEEP_FOR_RESUME -> {
                        Log.i(
                            TAG,
                            "worker stopped by system: task=$taskId status=${task.status}, kept for resume"
                        )
                    }

                    BackgroundTaskRules.WorkerStopAction.NONE -> {
                        Log.i(TAG, "worker stopped: task=$taskId status=${task.status}, nothing to do")
                    }
                }
                cancelForegroundNotification(task)
            }
        }
    }

    // ============================================================
    // 前台通知（dataSync 类型，运行期间常驻可取消）
    // ============================================================

    private fun notificationId(task: BackgroundTaskEntity): Int = task.id.hashCode()

    private fun buildNotification(task: BackgroundTaskEntity, text: String): Notification {
        val typeLabel = when (task.taskType) {
            BackgroundTaskTypes.RADIO_AUDIO_GENERATION -> "电台节目音频生成"
            else -> "后台任务"
        }
        val cancelIntent = PendingIntent.getBroadcast(
            applicationContext,
            task.id.hashCode(),
            Intent(applicationContext, BackgroundTaskActionReceiver::class.java).apply {
                action = BackgroundTaskActionReceiver.ACTION_CANCEL_TASK
                putExtra(BackgroundTaskActionReceiver.EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, NotificationSoundManager.BACKGROUND_TASK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(typeLabel)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .addAction(0, applicationContext.getString(R.string.notification_action_cancel_task), cancelIntent)
            .build()
    }

    private fun buildForegroundInfo(task: BackgroundTaskEntity, text: String): ForegroundInfo =
        ForegroundInfo(notificationId(task), buildNotification(task, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** 逐句/阶段进度刷新（复用同一通知 ID 更新 FGS 通知内容）。 */
    private fun updateForegroundNotification(task: BackgroundTaskEntity, progressJson: String) {
        runCatching {
            val progress = runCatching {
                me.rerere.rikkahub.common.JsonInstant.decodeFromString(
                    RadioAudioTaskProgress.serializer(), progressJson
                )
            }.getOrNull()
            val text = when {
                progress == null -> "任务执行中"
                progress.phase == RadioAudioTaskProgress.PHASE_MERGING -> "分句合成完成，正在合并音频…"
                progress.phase == RadioAudioTaskProgress.PHASE_ENCODING -> "合并完成，正在编码音频…"
                progress.total > 0 -> "第 ${progress.completed}/${progress.total} 句"
                else -> "任务执行中"
            }
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId(task), buildNotification(task, text))
        }
    }

    private fun cancelForegroundNotification(task: BackgroundTaskEntity) {
        runCatching {
            NotificationManagerCompat.from(applicationContext).cancel(notificationId(task))
        }
    }

    companion object {
        private const val TAG = "BackgroundTaskWorker"
        const val KEY_TASK_ID = "taskId"

        /** 任务排队超时文案（deadline 兜底，含排队等待的产品定稿语义）。 */
        const val TIMEOUT_MESSAGE = "任务执行超时（总时限自创建起 50 分钟，含排队等待时间）"

        /** 认领冲突重试上限：超限放弃（任务由启动恢复链兜底）。 */
        private const val CLAIM_CONFLICT_MAX_ATTEMPTS = 10
    }
}
