package me.rerere.rikkahub.data.backgroundtask

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskCallbackState
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskStatus
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 通用后台任务纯决策规则（不依赖 Android API，可单测）。
 *
 * deadline 语义（产品定稿）：50 分钟为任务自创建到完成的**总生命周期上限，含排队等待时间**；
 * TTS 预计 30 分钟内完成，50 分钟容纳网络波动 / 系统调度延迟 / 中断恢复，防止任务无限挂起。
 * 容错策略 = 逐句 chunk 持久化 + 安全恢复 + 有限重试 + deadline 兜底；不追求连续运行满 50 分钟。
 */
object BackgroundTaskRules {

    // ============================================================
    // 常量
    // ============================================================

    /** 任务总生命周期上限（自 created_at 起算，含排队）。 */
    const val TASK_TOTAL_DEADLINE_MS: Long = 50 * 60_000L

    /** 执行租约：过期后其他 Worker 实例可安全重认领。注意：租约过期只代表「可认领」，
     *  实际重调度延迟 = max(租约剩余, WorkManager 重试 backoff)，不代表 60s 内必然重跑。 */
    const val EXECUTION_LEASE_MS: Long = 60_000L

    /** 任务级最大重试次数（WorkManager Result.retry 次数上限）。 */
    const val MAX_TASK_RETRIES: Int = 3

    /** 回调投递认领租约（覆盖 busy-wait + 消息注入窗口；生成阶段由 INJECTED 状态 + 会话内容恢复，不靠租约）。 */
    const val CALLBACK_CLAIM_LEASE_MS: Long = 10 * 60_000L

    /** 恢复扫描时 tmp 工作目录的保留时长（超过后由清理任务删除）。 */
    const val STALE_WORK_DIR_RETENTION_MS: Long = 24 * 60 * 60_000L

    // ============================================================
    // deadline
    // ============================================================

    fun isDeadlineExceeded(deadlineAt: Long, now: Long): Boolean = now >= deadlineAt

    fun deadlineFromCreation(createdAt: Long): Long = createdAt + TASK_TOTAL_DEADLINE_MS

    // ============================================================
    // 创建冲突决策
    // ============================================================

    /** 同 ref_key 活跃任务候选（调用方从 DB 查询 queued/running/committing 任务后构造）。 */
    data class ConflictCandidate(
        val taskId: String,
        val contentHash: String,
        val createdAt: Long,
        val isExecuting: Boolean,
    )

    sealed interface ConflictDecision {
        /** 幂等复用：同节目同文字稿已有活跃任务，返回其 task_id */
        data class Reuse(val taskId: String) : ConflictDecision

        /** 拒绝创建：同节目不同文字稿任务进行中，返回既有 task_id 供 Agent 查询 */
        data class Reject(val existingTaskId: String) : ConflictDecision

        /** 无冲突，允许创建 */
        data object Proceed : ConflictDecision
    }

    /**
     * 同节目冲突决策：
     * - 同 episode 同 content_hash：幂等复用已有任务（优先执行中 > 排队中，同级取最新创建）；
     * - 同 episode 不同 content_hash：拒绝（文字稿已变化，旧任务结果对新稿无意义），返回既有任务 ID。
     */
    fun decideConflict(
        activeTasks: List<ConflictCandidate>,
        contentHash: String
    ): ConflictDecision {
        if (activeTasks.isEmpty()) return ConflictDecision.Proceed

        val sameHash = activeTasks.filter { it.contentHash == contentHash }
        if (sameHash.isNotEmpty()) {
            val best = sameHash.sortedWith(
                compareByDescending<ConflictCandidate> { it.isExecuting }.thenByDescending { it.createdAt }
            ).first()
            return ConflictDecision.Reuse(best.taskId)
        }
        val newest = activeTasks.sortedWith(
            compareByDescending<ConflictCandidate> { it.isExecuting }.thenByDescending { it.createdAt }
        ).first()
        return ConflictDecision.Reject(newest.taskId)
    }

    // ============================================================
    // Worker 停止决策（区分用户取消与系统停止）
    // ============================================================

    /** Worker 被停止时的处置动作。 */
    enum class WorkerStopAction {
        /** 用户取消（DB 已 CANCELLED 终态）：清理任务产物 + 撤通知 */
        CLEANUP_CANCELLED,

        /** 系统停止（网络约束变化 / dataSync onTimeout / 系统停 FGS）：
         *  保持任务状态与 chunk 断点数据，等待 WorkManager 重跑续传 */
        KEEP_FOR_RESUME,

        /** 终态（succeeded/failed）或未知状态：无需处理 */
        NONE,
    }

    /**
     * Worker 被停止时的处置决策：以 DB 状态为准区分「用户取消」与「系统停止」。
     * - CANCELLED = 用户取消（Manager.cancelTask 先落终态再取消 Work）→ 清理产物；
     * - queued/running/committing = 系统停止 → 保持状态与 chunk 断点续传数据不清理，
     *   WorkManager 按退避重跑后由租约过期重认领续传（进程死亡路径同样依赖此机制）；
     *   旧实现对一切取消统一 cancelTask + 删产物，一次瞬时断网就会把跑了半小时的
     *   任务永久取消，13.3 恢复链路因此不可达。
     * - succeeded/failed = 终态 → 无需处理。
     */
    fun decideWorkerStopAction(statusName: String?): WorkerStopAction {
        val status = BackgroundTaskStatus.from(statusName) ?: return WorkerStopAction.NONE
        return when (status) {
            BackgroundTaskStatus.CANCELLED -> WorkerStopAction.CLEANUP_CANCELLED
            BackgroundTaskStatus.QUEUED,
            BackgroundTaskStatus.RUNNING,
            BackgroundTaskStatus.COMMITTING,
            -> WorkerStopAction.KEEP_FOR_RESUME
            BackgroundTaskStatus.SUCCEEDED,
            BackgroundTaskStatus.FAILED,
            -> WorkerStopAction.NONE
        }
    }

    // ============================================================
    // 回调投递决策
    // ============================================================

    /**
     * 是否应当立即投递回调（唤起 Agent）。
     * - 仅 notify_agent / continue_workflow 唤起；notify_user 只更新 UI 与通知；
     * - 会话忙（有活跃生成 Job / aiTyping）时延后（保持 PENDING，由生成结束钩子 / 启动扫描重试）。
     */
    fun shouldDeliverCallback(
        callbackState: String,
        completionPolicy: String,
        conversationBusy: Boolean
    ): Boolean {
        val isWakeupPolicy = completionPolicy == CompletionPolicies.NOTIFY_AGENT ||
            completionPolicy == CompletionPolicies.CONTINUE_WORKFLOW
        val isPending = callbackState == BackgroundTaskCallbackState.PENDING.name
        val isDeliverable = isWakeupPolicy && isPending && !conversationBusy
        return isDeliverable
    }

    // ============================================================
    // INJECTED 状态恢复决策（基于会话内容，不基于时间租约）
    // ============================================================

    enum class InjectedRecovery {
        /** 无需处理 */
        NONE,
        /** 回调轮次已产生响应（或消息已非末节点），标记 COMPLETED，不重复唤起 */
        MARK_COMPLETED,
        /** 回调消息仍是会话末节点且无响应：以既有消息为锚直接续跑生成，不重复注入 */
        RESUME_GENERATION,
    }

    /**
     * 进程在「消息已注入、回调轮次未结束」区间崩溃后的恢复决策：
     * - 有 ASSISTANT 响应 → Agent 已执行 → COMPLETED；
     * - 无响应且回调消息是会话末节点 → 续跑生成（resume 模式）；
     * - 无响应但消息已非末节点（用户已插入新消息）→ 保守 COMPLETED（用户消息自身会触发生成，
     *   回调指令在上下文中白名单可见，Agent 可处理；不劫持用户的待生成轮次）。
     */
    fun decideInjectedRecovery(
        hasAssistantResponseAfterCallback: Boolean,
        isCallbackMessageLastNode: Boolean
    ): InjectedRecovery {
        val hasResponse = hasAssistantResponseAfterCallback
        val resumeEligible = !hasResponse && isCallbackMessageLastNode
        val recovery = when {
            hasResponse -> InjectedRecovery.MARK_COMPLETED
            resumeEligible -> InjectedRecovery.RESUME_GENERATION
            else -> InjectedRecovery.MARK_COMPLETED
        }
        return recovery
    }

    // ============================================================
    // 回调指令消息（稳定 ID + 结构化内容 + 解析）
    // ============================================================

    /**
     * 回调内部消息稳定 ID：由 task_id 确定性派生。
     * 注入前按此 ID 查库判重，保证同一任务事件永不重复注入（App 崩溃在任意窗口均安全）。
     */
    @OptIn(ExperimentalUuidApi::class)
    fun callbackMessageId(taskId: String): Uuid {
        val digest = MessageDigest.getInstance("MD5")
            .digest("background_task_callback:$taskId".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        val canonical = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        return Uuid.parse(canonical)
    }

    /**
     * 是否为合法的后台任务 ID（background_tasks 主键统一为 Uuid.random().toString()）。
     * UI 挂载任务卡片前必须校验：agent_tasks / schedule 等其他任务体系也会在工具结果里
     * 返回数字型 task_id，未校验会误挂载出「任务记录不存在或已被清理」的占位卡片。
     */
    @OptIn(ExperimentalUuidApi::class)
    fun isBackgroundTaskId(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching { Uuid.parse(raw) }.isSuccess
    }

    /**
     * 构造注入会话的回调指令文本（USER 消息，skipContext=true + MessageSource.AGENT_TASK：
     * UI 不可见；上下文白名单完整带入不截断）。JSON 块供 parseCallbackInstruction 在 resume 模式重新解析。
     */
    fun buildCallbackInstruction(
        taskId: String,
        taskType: String,
        refKey: String,
        status: String,
        completionPolicy: String,
        resultJson: String?,
        errorMessage: String?,
        followUpInstruction: String?
    ): String {
        val data = CallbackInstructionData(
            callbackId = callbackMessageId(taskId).toString(),
            taskId = taskId,
            taskType = taskType,
            refKey = refKey,
            status = status,
            completionPolicy = completionPolicy,
            result = resultJson?.let { runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull() },
            error = errorMessage,
            followUpInstruction = followUpInstruction,
        )
        val json = JsonInstant.encodeToString(CallbackInstructionData.serializer(), data)
        val resultSummary = when (status) {
            "succeeded" -> "任务执行成功，结果见上方 JSON 的 result 字段"
            "failed" -> "任务执行失败，原因：${errorMessage ?: "未知"}"
            else -> "任务状态：$status"
        }
        return buildString {
            appendLine(BackgroundTaskCallbackHeader.INSTRUCTION_HEADER)
            appendLine(json)
            appendLine()
            appendLine("以上是系统后台任务完成事件，不是用户输入的对话消息。请根据事件内容决定后续动作：")
            appendLine("- $resultSummary。")
            appendLine("- 任务成功：用自然语言告知用户结果（是否需要进一步操作由用户决定）。")
            appendLine("- 任务失败：向用户解释失败原因，不得虚构成功结果；不要立即自动重试，除非用户要求。")
            appendLine("- 任务未完成/仍执行中：如实告知用户任务仍在进行，可通过 get_background_task 查询。")
            appendLine("- continue_workflow 策略：按 follow_up_instruction 说明的后续动作继续推进（该策略表示用户已在原始指令中授权此工作流）。")
            appendLine("- 不要重复执行已经成功的任务；不要把本系统事件当作用户的新要求。")
        }.trimEnd()
    }

    /** resume 模式从已注入消息解析出的回调上下文。 */
    data class ParsedCallbackInstruction(
        val callbackId: String,
        val taskId: String,
        val taskType: String,
        val refKey: String,
        val status: String,
        val completionPolicy: String,
        val followUpInstruction: String?,
    )

    /** 从已注入消息文本解析回调指令（resume 模式恢复任务上下文）。解析失败返回 null。 */
    fun parseCallbackInstruction(text: String): ParsedCallbackInstruction? {
        val headerIndex = text.indexOf(BackgroundTaskCallbackHeader.INSTRUCTION_HEADER)
        if (headerIndex < 0) return null
        // JSON 块独占一行（buildCallbackInstruction 以 appendLine 输出）：
        // 取头部之后第一行以 '{' 开头的完整行，避免嵌套 JSON（result 字段）被首'}'截断
        val jsonLine = text.substring(headerIndex)
            .lines()
            .firstOrNull { it.trimStart().startsWith("{") }
            ?.trim() ?: return null
        val data = runCatching {
            JsonInstant.decodeFromString(CallbackInstructionData.serializer(), jsonLine)
        }.getOrNull() ?: return null
        val parsed = ParsedCallbackInstruction(
            callbackId = data.callbackId,
            taskId = data.taskId,
            taskType = data.taskType,
            refKey = data.refKey,
            status = data.status,
            completionPolicy = data.completionPolicy,
            followUpInstruction = data.followUpInstruction,
        )
        return parsed
    }
}

/** 头部常量独立对象，便于 UI/测试引用。 */
object BackgroundTaskCallbackHeader {
    const val INSTRUCTION_HEADER = "【系统自动化指令 - 后台任务完成】"
}

/** 回调指令 JSON 块结构（内部消息格式，非对外 API）。 */
@Serializable
internal data class CallbackInstructionData(
    val callbackId: String,
    val taskId: String,
    val taskType: String,
    val refKey: String,
    val status: String,
    val completionPolicy: String,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
    val followUpInstruction: String? = null,
)
