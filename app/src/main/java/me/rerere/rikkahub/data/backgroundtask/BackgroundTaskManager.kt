package me.rerere.rikkahub.data.backgroundtask

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskStatus
import me.rerere.rikkahub.core.data.repository.BackgroundTaskRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.BackgroundTaskWorker
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 任务创建结果。 */
sealed interface CreateTaskOutcome {
    /** 已创建新任务（task_id 已入队） */
    data class Created(val taskId: String) : CreateTaskOutcome

    /** 幂等复用：同业务同内容已有活跃任务，返回其 task_id */
    data class Reused(val taskId: String) : CreateTaskOutcome

    /** 拒绝创建：同业务不同内容任务进行中 */
    data class Rejected(val existingTaskId: String, val message: String) : CreateTaskOutcome
}

/** 取消任务结果。 */
sealed interface CancelTaskOutcome {
    data object Cancelled : CancelTaskOutcome
    data class NotCancellable(val reason: String) : CancelTaskOutcome
    data object NotFound : CancelTaskOutcome
}

/** 回调投递结果（Agent 唤起尝试）。 */
sealed interface CallbackDeliveryOutcome {
    /** 已注入回调消息并开始新轮次 */
    data object Delivered : CallbackDeliveryOutcome

    /** 会话忙，延后（保持 PENDING） */
    data object Deferred : CallbackDeliveryOutcome

    /** 目标 Agent/会话不可达：ABANDONED + 兜底通知（任务结果保留） */
    data class Abandoned(val reason: String) : CallbackDeliveryOutcome
}

/**
 * 通用后台任务管理器（AGENTS.md 通用后台任务机制章节）。
 *
 * 职责：创建（冲突检查/落库/入队）、取消、查询、执行恢复、回调投递。
 * 执行状态机的原子性全部由 BackgroundTaskDao 条件 UPDATE 保证；本类不做跨行事务，
 * 冲突检查以 per-refKey Mutex（单进程内串行化创建）+ 查询-判定-插入串行链实现。
 *
 * 回调唤起依赖 ChatService：通过 KoinComponent 惰性注入打破
 * ChatService ↔ Manager 的构造依赖环（ChatService 工具装配需要 Manager，Manager 回调需要 ChatService）。
 */
@OptIn(ExperimentalUuidApi::class)
class BackgroundTaskManager(
    private val context: Context,
    private val backgroundTaskRepository: BackgroundTaskRepository,
    private val appScope: CoroutineScope,
    private val radioAudioExecutor: RadioAudioGenerationExecutor,
) : KoinComponent {

    private val chatService: me.rerere.rikkahub.service.ChatService by inject()
    private val settingsStore: SettingsStore by inject()

    /** 执行器注册表：新长耗时任务类型在此登记 */
    private val executors: Map<String, BackgroundTaskExecutor> = buildMap {
        put(radioAudioExecutor.taskType, radioAudioExecutor)
    }

    /** per-refKey 创建互斥（单进程假设：全部创建路径都经本类，见 AGENTS.md 并发安全章节） */
    private val creationLocks = ConcurrentHashMap<String, Mutex>()

    fun executorFor(taskType: String): BackgroundTaskExecutor? = executors[taskType]

    private fun creationLock(refKey: String): Mutex =
        creationLocks.computeIfAbsent(refKey) { Mutex() }

    // ============================================================
    // 创建 / 取消 / 查询
    // ============================================================

    /**
     * 创建后台任务并提交执行（立即返回，不等待执行）。
     *
     * @param conflictDecider 业务冲突决策（如电台的同节目同稿复用/异稿拒绝）；
     *   传入时在锁内执行「查活跃任务 → 决策」，Reuse/Rejected 直接短路返回
     */
    suspend fun createTask(
        taskType: String,
        refKey: String,
        payloadJson: String,
        completionPolicy: String,
        agentId: String?,
        conversationId: String?,
        sourceTurnId: String?,
        conflictDecider: ((List<BackgroundTaskEntity>) -> BackgroundTaskRules.ConflictDecision)? = null,
        now: Long = System.currentTimeMillis()
    ): CreateTaskOutcome = creationLock(refKey).withLock {
        if (conflictDecider != null) {
            val existing = backgroundTaskRepository.getActiveTasksByRef(taskType, refKey)
            when (val decision = conflictDecider(existing)) {
                is BackgroundTaskRules.ConflictDecision.Reuse ->
                    return@withLock CreateTaskOutcome.Reused(decision.taskId)

                is BackgroundTaskRules.ConflictDecision.Reject ->
                    return@withLock CreateTaskOutcome.Rejected(
                        existingTaskId = decision.existingTaskId,
                        message = "该业务已有进行中的生成任务（内容不同），请等待其完成或先取消"
                    )

                BackgroundTaskRules.ConflictDecision.Proceed -> Unit
            }
        }

        val task = BackgroundTaskEntity(
            id = Uuid.random().toString(),
            taskType = taskType,
            refKey = refKey,
            payloadJson = payloadJson,
            completionPolicy = completionPolicy,
            agentId = agentId,
            conversationId = conversationId,
            sourceTurnId = sourceTurnId,
            createdAt = now,
            // deadline = created_at + 50min：总生命周期上限，含排队等待（产品定稿语义）
            deadlineAt = BackgroundTaskRules.deadlineFromCreation(now)
        )
        backgroundTaskRepository.insertTask(task)
        enqueueWorker(task.id)
        Log.i(TAG, "background task created: id=${task.id} type=$taskType ref=$refKey policy=$completionPolicy")
        CreateTaskOutcome.Created(task.id)
    }

    /**
     * 取消任务（用户主动 / cancel_background_task 工具）。
     * 仅 queued/running 可取消；committing（提交原子边界内）与终态明确拒绝。
     */
    suspend fun cancelTask(taskId: String): CancelTaskOutcome = withContext(Dispatchers.IO) {
        val task = backgroundTaskRepository.getTaskById(taskId)
            ?: return@withContext CancelTaskOutcome.NotFound
        val status = BackgroundTaskStatus.from(task.status)
        if (status == null || status.isTerminal) {
            return@withContext CancelTaskOutcome.NotCancellable("任务已完成，无法取消")
        }
        if (status == BackgroundTaskStatus.COMMITTING) {
            return@withContext CancelTaskOutcome.NotCancellable("任务正在提交结果，无法取消")
        }
        val cancelled = backgroundTaskRepository.cancelTask(taskId, System.currentTimeMillis()) > 0
        if (!cancelled) {
            return@withContext CancelTaskOutcome.NotCancellable("任务状态已变化，无法取消")
        }
        // 停止 Work：运行中 Worker 收到取消 → 标记已由本方法完成 + 产物清理；
        // 排队中（Worker 未启动）则无清理路径，这里直接清理产物与临时目录
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(taskId))
        if (task.status == BackgroundTaskStatus.QUEUED.name) {
            executorFor(task.taskType)?.let { executor ->
                runCatching { executor.cleanup(task) }
                    .onFailure { Log.w(TAG, "取消排队任务清理失败: $taskId", it) }
            }
        }
        Log.i(TAG, "background task cancelled: $taskId (was ${task.status})")
        CancelTaskOutcome.Cancelled
    }

    fun observeTask(taskId: String) = backgroundTaskRepository.observeTaskById(taskId)

    /** 读取任务快照（get/cancel 工具用）。 */
    suspend fun getTaskById(taskId: String): BackgroundTaskEntity? =
        backgroundTaskRepository.getTaskById(taskId)

    /** 按会话查最近任务（get_background_task 无参查询）。 */
    suspend fun getRecentTasks(conversationId: String, limit: Int = 10): List<BackgroundTaskEntity> =
        backgroundTaskRepository.getRecentTasksByConversation(conversationId, limit)

    /** 构造 get_background_task 的任务摘要（与任务卡片同源：只读 background_tasks）。 */
    fun buildTaskSummary(task: BackgroundTaskEntity): JsonObject = buildJsonObject {
        put("task_id", task.id)
        put("task_type", task.taskType)
        put("status", task.status.lowercase())
        put("status_desc", statusDescription(task))
        put("completion_policy", task.completionPolicy)
        put("callback_state", task.callbackState.lowercase())
        put("created_at", task.createdAt)
        put("started_at", task.startedAt ?: 0L)
        put("completed_at", task.completedAt ?: 0L)
        put("deadline_at", task.deadlineAt)
        put("retry_count", task.retryCount)
        put("error_message", task.errorMessage)
        put(
            "progress",
            task.progressJson?.let { runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull() }
                ?: kotlinx.serialization.json.JsonNull
        )
        put(
            "result",
            task.resultJson?.let { runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull() }
                ?: kotlinx.serialization.json.JsonNull
        )
    }

    private fun statusDescription(task: BackgroundTaskEntity): String = when (BackgroundTaskStatus.from(task.status)) {
        BackgroundTaskStatus.QUEUED -> "任务排队中（等待执行）"
        BackgroundTaskStatus.RUNNING -> "任务正在生成"
        BackgroundTaskStatus.COMMITTING -> "生成完成，正在提交结果"
        BackgroundTaskStatus.SUCCEEDED -> "任务已成功"
        BackgroundTaskStatus.FAILED -> "任务已失败"
        BackgroundTaskStatus.CANCELLED -> "任务已取消"
        null -> "未知状态"
    }

    // ============================================================
    // WorkManager 调度
    // ============================================================

    private fun uniqueWorkName(taskId: String) = "background_task_$taskId"

    /** 入队执行 Worker（KEEP 幂等：重入队不会重复执行）。 */
    private fun enqueueWorker(taskId: String) {
        val request = OneTimeWorkRequestBuilder<BackgroundTaskWorker>()
            .setInputData(workDataOf(BackgroundTaskWorker.KEY_TASK_ID to taskId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // 项目惯例不设 backoff；此处例外：进程被杀后需要较频繁的重试机会尽快续跑，
            // 线性 30s 避免指数退避把 50 分钟 deadline 消耗在等待上（AGENTS.md 已注明）
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("background_task")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(taskId), ExistingWorkPolicy.KEEP, request)
    }

    // ============================================================
    // 启动恢复（EvoliaApp.onCreate 异步调用）
    // ============================================================

    /**
     * 启动恢复三步（幂等，重复调用安全）：
     * 1. 活跃任务对账：WorkManager 无活跃 work → 重置过期租约任务回 queued 并重新入队；
     *    deadline 已过的任务直接置 failed（不复活）；
     * 2. 同 ref_key 多活跃任务清理（理论不可达，防御性对账）：保留最新，其余取消；
     * 3. 回调恢复：过期 DELIVERING → PENDING，PENDING 重新尝试投递。
     */
    suspend fun recoverTasks() = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()

        val activeTasks = backgroundTaskRepository.getAllActiveTasks()
        for (task in activeTasks) {
            val status = BackgroundTaskStatus.from(task.status) ?: continue
            val deadlineExceeded = BackgroundTaskRules.isDeadlineExceeded(task.deadlineAt, now)

            if (deadlineExceeded) {
                val reason = "任务执行超时（总时限自创建起 50 分钟，含排队等待时间）"
                if (status == BackgroundTaskStatus.QUEUED) {
                    backgroundTaskRepository.failQueuedTask(task.id, reason, now)
                } else if (backgroundTaskRepository.resetStaleToQueued(task.id, now) > 0) {
                    // 租约已过期（无活跃执行者）才可安全失败
                    backgroundTaskRepository.failQueuedTask(task.id, reason, now)
                }
                if (BackgroundTaskStatus.from(backgroundTaskRepository.getTaskById(task.id)?.status)
                        ?.isTerminal == true
                ) {
                    backgroundTaskRepository.markCallbackPending(task.id)
                    dispatchCallbacksSync(conversationId = null)
                }
                continue
            }

            val hasActiveWork = runCatching {
                wm.getWorkInfosForUniqueWork(uniqueWorkName(task.id)).get().any { !it.state.isFinished }
            }.getOrDefault(false)

            if (!hasActiveWork) {
                // WorkManager 丢失（force-stop / 预算耗尽后清理）：租约过期的执行中任务重置回 queued
                if (status != BackgroundTaskStatus.QUEUED) {
                    backgroundTaskRepository.resetStaleToQueued(task.id, now)
                }
                enqueueWorker(task.id)
                Log.i(TAG, "recovered task re-enqueued: ${task.id} (was ${task.status})")
            }
        }

        // 同 ref_key 多活跃任务对账（保留最新创建的一个，其余取消）
        activeTasks.groupBy { it.taskType to it.refKey }
            .filter { it.value.size > 1 }
            .forEach { (_, group) ->
                val keep = group.sortedByDescending { it.createdAt }.first()
                group.filter { it.id != keep.id }.forEach { duplicate ->
                    Log.w(TAG, "duplicate active task for ref=${duplicate.refKey}, cancelling ${duplicate.id}")
                    cancelTask(duplicate.id)
                }
            }

        // 3. 回调恢复：过期 DELIVERING → PENDING、INJECTED 按会话内容恢复、PENDING 重试投递
        dispatchCallbacksSync(conversationId = null)
    }

    // ============================================================
    // 回调投递（Agent 后台唤起）
    // ============================================================

    /**
     * 回调投递入口（三个触发时机：任务完成时 / ChatService 每轮生成结束 / App 启动恢复）。
     * @param conversationId 非空时只处理该会话的回调（生成结束钩子场景）
     */
    fun dispatchCallbacks(conversationId: Uuid? = null) {
        appScope.launch(Dispatchers.IO) {
            runCatching { dispatchCallbacksSync(conversationId) }
                .onFailure { Log.e(TAG, "dispatchCallbacks failed", it) }
        }
    }

    private suspend fun dispatchCallbacksSync(conversationId: Uuid?) {
        val now = System.currentTimeMillis()
        // 过期 DELIVERING 回退 PENDING（进程崩溃在「已认领未注入」窗口的恢复）
        if (conversationId == null) {
            backgroundTaskRepository.resetAllStaleDeliverings(now - BackgroundTaskRules.CALLBACK_CLAIM_LEASE_MS)
        }

        // INJECTED 恢复（基于会话内容判定，不基于时间租约）：
        // 有 ASSISTANT 响应 → COMPLETED；无响应且回调消息是末节点 → 以既有消息为锚续跑生成（不重复注入）
        val injectedTasks = backgroundTaskRepository.getInjectedCallbackTasks()
            .filter { conversationId == null || it.conversationId == conversationId.toString() }
        for (task in injectedTasks) {
            val recovery = runCatching { chatService.resolveInjectedCallbackRecovery(task) }
                .getOrElse { error ->
                    Log.e(TAG, "resolveInjectedCallbackRecovery failed: ${task.id}", error)
                    BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED
                }
            when (recovery) {
                BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED ->
                    backgroundTaskRepository.markCallbackCompleted(
                        task.id, error = "回调轮次未确认完成（进程中断），结果可通过查询获取"
                    )

                BackgroundTaskRules.InjectedRecovery.RESUME_GENERATION -> {
                    val busy = isConversationBusy(task.conversationId)
                    if (!busy) {
                        runCatching { chatService.executeBackgroundTaskCallback(task, resume = true) }
                            .onFailure { Log.e(TAG, "resume callback failed: ${task.id}", it) }
                    }
                }

                BackgroundTaskRules.InjectedRecovery.NONE -> Unit
            }
        }

        val candidates = backgroundTaskRepository.getPendingCallbackTasks()
            .filter { conversationId == null || it.conversationId == conversationId.toString() }
        for (task in candidates) {
            val busy = isConversationBusy(task.conversationId)
            if (!BackgroundTaskRules.shouldDeliverCallback(task.callbackState, task.completionPolicy, busy)) continue
            dispatchCallbackFor(task)
        }
    }

    /** 指定任务的回调投递（任务完成后的即时触发）。 */
    fun dispatchCallbacksForTask(taskId: String) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val task = backgroundTaskRepository.getTaskById(taskId) ?: return@runCatching
                val busy = isConversationBusy(task.conversationId)
                if (BackgroundTaskRules.shouldDeliverCallback(task.callbackState, task.completionPolicy, busy)) {
                    dispatchCallbackFor(task)
                }
            }.onFailure { Log.e(TAG, "dispatchCallbacksForTask failed: $taskId", it) }
        }
    }

    // 回调状态机透传（ChatService 回调唤起流程写入）
    suspend fun markCallbackInjected(taskId: String): Boolean =
        backgroundTaskRepository.markCallbackInjected(taskId) > 0

    /** 回调轮次结束（成功/取消/异常均算结束）；随后消化该会话积压回调。 */
    suspend fun markCallbackCompleted(taskId: String, error: String?): Boolean =
        backgroundTaskRepository.markCallbackCompleted(taskId, error) > 0

    private suspend fun dispatchCallbackFor(task: BackgroundTaskEntity) {
        val claimed = backgroundTaskRepository.claimCallback(task.id, System.currentTimeMillis()) > 0
        if (!claimed) return

        val outcome = runCatching { chatService.executeBackgroundTaskCallback(task) }
            .getOrElse { error ->
                Log.e(TAG, "callback delivery failed: ${task.id}", error)
                // 认领租约内失败：回 PENDING 等待下次投递（生成结束钩子 / 启动扫描）
                backgroundTaskRepository.resetStaleDelivering(
                    task.id,
                    expiredBefore = Long.MAX_VALUE,
                    error = "投递异常：${error.message ?: error.javaClass.simpleName}"
                )
                return
            }
        when (outcome) {
            me.rerere.rikkahub.service.CallbackDeliveryResult.Delivered -> Unit
            me.rerere.rikkahub.service.CallbackDeliveryResult.AgentMissing ->
                abandonCallback(task, "发起任务的助手已被删除，结果仅保留在任务记录中")
            me.rerere.rikkahub.service.CallbackDeliveryResult.ConversationMissing ->
                abandonCallback(task, "发起会话已不存在，结果仅保留在任务记录中")
            me.rerere.rikkahub.service.CallbackDeliveryResult.Busy ->
                // 竞态下变忙：回 PENDING 等待生成结束钩子重投
                backgroundTaskRepository.resetStaleDelivering(
                    task.id, expiredBefore = Long.MAX_VALUE, error = "会话忙，延后投递"
                )
        }
    }

    private suspend fun abandonCallback(task: BackgroundTaskEntity, reason: String) {
        backgroundTaskRepository.markCallbackAbandoned(task.id, reason)
        sendResultNotification(task, aborted = true)
    }

    // ============================================================
    // 通知
    // ============================================================

    /** 会话是否有活跃生成轮（回调投递的忙判定；ChatService 内部状态）。 */
    private fun isConversationBusy(conversationId: String?): Boolean {
        if (conversationId.isNullOrBlank()) return false
        return runCatching { chatService.isConversationBusy(kotlin.uuid.Uuid.parse(conversationId)) }
            .getOrDefault(false)
    }

    /** 任务完成 / 回调不可达的兜底系统通知（ai_messages 渠道，gate notificationAiReplyEnabled）。 */
    fun sendResultNotification(task: BackgroundTaskEntity, aborted: Boolean = false) {
        runCatching {
            if (!settingsStore.settingsFlow.value.notificationAiReplyEnabled) return
            val title = when {
                aborted -> "后台任务结果提醒"
                task.status == BackgroundTaskStatus.SUCCEEDED.name -> "后台任务已完成"
                else -> "后台任务失败"
            }
            val text = when {
                aborted -> task.callbackLastError ?: task.errorMessage ?: "任务结果已保存，可打开对话中的任务卡片查看"
                task.status == BackgroundTaskStatus.SUCCEEDED.name -> "任务结果已保存，可打开对话中的任务卡片查看"
                else -> task.errorMessage ?: "任务失败，可打开对话中的任务卡片查看原因"
            }
            val manager = NotificationManagerCompat.from(context)
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, task.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, NotificationSoundManager.AI_MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(task.id.hashCode(), notification)
        }.onFailure { Log.w(TAG, "sendResultNotification failed: ${task.id}", it) }
    }

    companion object {
        private const val TAG = "BackgroundTaskManager"
    }
}
