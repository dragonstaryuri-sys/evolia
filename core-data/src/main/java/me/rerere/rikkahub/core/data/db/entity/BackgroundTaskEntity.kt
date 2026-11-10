package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 通用后台任务状态。
 *
 * 状态机（全部变更使用 DAO 条件 UPDATE 原子完成，终态不可被覆盖）：
 * ```
 * queued ──claim──▶ running ──toCommitting──▶ committing ──▶ succeeded
 *   │                 │  ▲                        │
 *   │                 │  └── reclaimStale ───────┘（租约过期重认领，committing 回 running）
 *   │                 └── backToQueued（本执行者重试回退，retry_count+1）
 *   ├──▶ cancelled（用户/工具取消，仅 queued/running 可取消）
 *   └──▶ failed（deadline 兜底 / 执行失败）
 * ```
 */
enum class BackgroundTaskStatus {
    QUEUED, RUNNING, COMMITTING, SUCCEEDED, FAILED, CANCELLED;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    companion object {
        fun from(value: String?): BackgroundTaskStatus? = value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/**
 * 任务结果回调（Agent 唤起）投递状态。与任务执行状态互相独立：
 * 任务 succeeded 不代表回调已送达；回调失败不影响任务结果持久化。
 *
 * - PENDING：待投递（任务终态后由执行方写入）
 * - DELIVERING：已认领正在注入回调消息（短窗口，受 callbackClaimedAt 租约保护）
 * - INJECTED：回调消息已注入会话（持久化进度标记；Agent 是否完成由会话内容判定）
 * - COMPLETED：回调轮次已结束（成功、被取消或异常均算结束）
 * - ABANDONED：目标 Agent/会话不可达，兜底走通知（任务结果保留）
 */
enum class BackgroundTaskCallbackState {
    NONE, PENDING, DELIVERING, INJECTED, COMPLETED, ABANDONED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == ABANDONED

    companion object {
        fun from(value: String?): BackgroundTaskCallbackState? =
            value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/**
 * 通用后台任务记录。background_tasks 表是任务状态与结果的唯一事实源：
 * Agent 查询（get_background_task）、UI 任务卡片、回调投递均只读此表，
 * 不依赖内存 State / WorkManager 临时状态 / 进程内 EventBus。
 */
@Entity(
    tableName = "background_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["task_type", "ref_key"]),
        Index(value = ["conversation_id"])
    ]
)
@Serializable
data class BackgroundTaskEntity(
    /** 任务 ID（UUID），即对外暴露的 task_id */
    @PrimaryKey
    val id: String,
    /** 任务类型，如 radio_audio_generation；Manager 据此路由到对应 Executor */
    @ColumnInfo(name = "task_type")
    val taskType: String,
    /** 业务引用键，如 radio_audio:{episodeId}；用于同业务冲突检查 */
    @ColumnInfo(name = "ref_key")
    val refKey: String,
    @ColumnInfo(name = "status")
    val status: String = BackgroundTaskStatus.QUEUED.name,
    /** 版本化任务参数 JSON（BackgroundTaskPayload），结构升级保持向后兼容 */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    /** 成功结果 JSON（BackgroundTaskResult），与 status 同一条条件 UPDATE 原子落库 */
    @ColumnInfo(name = "result_json")
    val resultJson: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    /** 发起任务的 Agent（助手）ID；回调唤起与所有权校验依据 */
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    /** 发起会话 ID；回调唤起目标与「本会话任务列表」查询依据 */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,
    /** 来源轮次锚点消息 ID（仅作来源关联，不用于恢复已结束 Turn） */
    @ColumnInfo(name = "source_turn_id")
    val sourceTurnId: String? = null,
    /** 完成策略：notify_user / notify_agent / continue_workflow */
    @ColumnInfo(name = "completion_policy")
    val completionPolicy: String = "notify_agent",
    @ColumnInfo(name = "callback_state")
    val callbackState: String = BackgroundTaskCallbackState.NONE.name,
    /** DELIVERING 认领时间；超过租约（回调租约 10min）视为进程中断，恢复扫描回 PENDING */
    @ColumnInfo(name = "callback_claimed_at")
    val callbackClaimedAt: Long? = null,
    @ColumnInfo(name = "callback_attempt_count")
    val callbackAttemptCount: Int = 0,
    @ColumnInfo(name = "callback_last_error")
    val callbackLastError: String? = null,
    /** 任务总生命周期上限计算基准：deadline_at = created_at + 50min（含排队等待，产品定稿语义） */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    /** 总生命周期截止时间；认领/逐句心跳/重试认领前都会检查，超时置 failed */
    @ColumnInfo(name = "deadline_at")
    val deadlineAt: Long,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    /** 当前执行租约所有者（每次 Worker 执行实例的 UUID）；终态写入必须匹配，防旧 Worker 覆盖新 Worker */
    @ColumnInfo(name = "execution_id")
    val executionId: String? = null,
    /** 执行租约到期时间；过期后其他 Worker 实例可安全重认领（进程被杀恢复） */
    @ColumnInfo(name = "lease_expires_at")
    val leaseExpiresAt: Long? = null,
    /** 执行进度 JSON（如 TTS 已完成句数/总句数/采样率），逐句心跳更新；仅作展示与恢复校验辅助，
     *  正确性事实源是 chunk 文件目录（见 ChunkResumeRules） */
    @ColumnInfo(name = "progress_json")
    val progressJson: String? = null,
)
