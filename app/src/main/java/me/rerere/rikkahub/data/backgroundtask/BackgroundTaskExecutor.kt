package me.rerere.rikkahub.data.backgroundtask

import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity

/**
 * 通用后台任务执行器接口。
 *
 * 新的长耗时工具接入方式：实现本接口并注册进 BackgroundTaskManager 的执行器表即可，
 * 调度 / 租约 / 状态机 / 回调 / 查询 / 取消 / 前台通知全部由通用机制承担。
 *
 * 执行者职责边界（状态机由 Worker 与 Executor 协作）：
 * - Worker：认领（claim/reclaim）、前台通知、deadline 与租约的运行期检查、终态落库（succeeded/failed）、
 *   重试回退（backToQueued）、取消标记；
 * - Executor：业务执行 + 提交原子边界（running→committing 的 [BackgroundTaskRepository.toCommitting]
 *   与业务资产提交），成功路径以 committing 状态结束返回 Success（由 Worker finalize）。
 */
interface BackgroundTaskExecutor {

    val taskType: String

    /**
     * 执行完整业务流程（生成产物 + 提交资产）。调用前提：任务已认领为 running 且持有租约。
     *
     * @param executionId 本次执行租约所有者 ID（提交原子边界 toCommitting 等条件 UPDATE 必须携带）
     * @param onProgress 长进度回调：Worker 据此续租约、更新 progress_json、刷新通知、检查 deadline。
     *   回调抛 [TaskDeadlineExceededException] = 应中止执行（保留诊断信息由 Worker 落库）；
     *   抛 [TaskLeaseLostException] = 租约被其他实例接管，执行者必须立即停止且不再触碰任务状态。
     */
    suspend fun execute(
        task: BackgroundTaskEntity,
        executionId: String,
        onProgress: suspend (progressJson: String) -> Unit
    ): ExecutionResult

    /** 完整清理：删除产物文件与工作目录（终态失败/取消/任务废弃时调用）。不得删除既有有效业务资产。 */
    suspend fun cleanup(task: BackgroundTaskEntity)

    /** 轻量清理：仅删除本次执行产生的最终产物文件，保留断点续传数据（任务级重试续跑前调用）。 */
    suspend fun cleanupTransientOutput(task: BackgroundTaskEntity)
}

sealed interface ExecutionResult {
    /** 业务成功，resultJson 将与终态同一条 UPDATE 原子落库 */
    data class Success(val resultJson: String) : ExecutionResult

    /** 可重试失败（如网络波动）：任务回 queued 保留续传数据，由 WorkManager 重试 */
    data class RetryableFailure(val message: String) : ExecutionResult

    /** 永久失败（参数损坏/文字稿变化/超时/重试上限）：任务置 failed，清理全部产物 */
    data class PermanentFailure(val message: String) : ExecutionResult
}

/** deadline 兜底触发（总生命周期上限，含排队等待）。 */
class TaskDeadlineExceededException :
    Exception("任务执行超时（总时限自创建起 50 分钟，含排队等待时间）")

/** 执行租约被其他实例接管（防御路径）：执行者必须停止且不再触碰任务状态。 */
class TaskLeaseLostException : Exception("执行租约已丢失")
