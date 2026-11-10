package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 通用后台任务查询/取消工具（常驻注册，Schema 极小）。
 *
 * 主动查询是回调机制的兜底能力，不是替代品（AGENTS.md 通用后台任务章节）：
 * - 回调负责主动通知与推进后续工作；
 * - 查询负责核实任务状态与获取完整结果；
 * - background_tasks 表是唯一事实源：无论 App 是否重启、回调是否失败，都可按 task_id 查询。
 *
 * Agent 使用规则（写入工具描述）：
 * - 任务创建后告知用户已开始，不要等待；
 * - 用户询问进度 / 回调未送达 / 需要进一步核实时调用查询；
 * - 任务未完成不得误报失败；失败必须按 error_message 如实转述，不得虚构成功。
 */
@OptIn(ExperimentalUuidApi::class)
fun buildBackgroundTaskTools(
    backgroundTaskManager: BackgroundTaskManager,
    assistantId: Uuid,
    conversationId: Uuid,
): List<Tool> {
    return listOf(
        buildGetBackgroundTaskTool(backgroundTaskManager, assistantId, conversationId),
        buildCancelBackgroundTaskTool(backgroundTaskManager, assistantId),
    )
}

/** 查询任务状态与结果（task_id 缺省 = 当前会话最近 10 个任务）。 */
private fun buildGetBackgroundTaskTool(
    backgroundTaskManager: BackgroundTaskManager,
    assistantId: Uuid,
    conversationId: Uuid,
): Tool = Tool(
    name = "get_background_task",
    description = "查询后台任务（如电台音频生成）的状态与结果。" +
        "status 取值：queued=排队中 / running=生成中 / committing=正在提交结果 / succeeded=已成功 / failed=已失败 / cancelled=已取消。" +
        "使用规则：任务刚创建时已返回 task_id，无需立即查询；用户询问进度、任务完成回调未送达、" +
        "或需要核实结果时调用；task_id 缺省时返回当前会话最近 10 个任务。" +
        "任务未完成时如实告知仍在进行，不得误报失败；失败时按 error_message 向用户解释，不得虚构成功。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "task_id",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("任务 ID；缺省时返回当前会话最近的任务列表"))
                    }
                )
            },
        )
    },
    systemPrompt = { _, _ -> "" },
    execute = { args ->
        val argObj = args as? JsonObject
        val taskId = argObj?.get("task_id")?.let { it as? JsonPrimitive }?.content?.takeIf { it.isNotBlank() }
        if (taskId == null) {
            // 列表查询：当前会话最近任务
            val tasks = backgroundTaskManager.getRecentTasks(conversationId.toString(), limit = 10)
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("count", JsonPrimitive(tasks.size))
                put(
                    "tasks",
                    JsonArray(tasks.map { backgroundTaskManager.buildTaskSummary(it) })
                )
            }
        } else {
            val task = backgroundTaskManager.getTaskById(taskId)
            when {
                task == null -> buildJsonObject {
                    put("success", JsonPrimitive(false))
                    put("error", JsonPrimitive("任务不存在：$taskId"))
                }

                !task.agentId.isNullOrBlank() && task.agentId != assistantId.toString() ->
                    // 所有权校验：不属于当前助手的任务拒绝返回内容
                    buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("该任务不属于当前助手，无权查询"))
                    }

                else -> buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("task", backgroundTaskManager.buildTaskSummary(task))
                }
            }
        }
    },
)

/** 取消指定后台任务（校验所有权与状态；仅 queued/running 可取消）。 */
private fun buildCancelBackgroundTaskTool(
    backgroundTaskManager: BackgroundTaskManager,
    assistantId: Uuid,
): Tool = Tool(
    name = "cancel_background_task",
    description = "取消指定的后台任务。仅排队中/执行中的任务可取消；任务正在提交结果或已完成时无法取消。" +
        "只能取消你自己（当前助手）发起的任务。用户明确要求取消任务时调用；任务完成后用户要求撤销时如实告知无法取消。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "task_id",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("要取消的任务 ID"))
                    }
                )
            },
            required = listOf("task_id"),
        )
    },
    systemPrompt = { _, _ -> "" },
    execute = { args ->
        val argObj = args as? JsonObject
        val taskId = argObj?.get("task_id")?.let { it as? JsonPrimitive }?.content?.takeIf { it.isNotBlank() }
        if (taskId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("task_id 为必填参数")) }
        } else {
            val task = backgroundTaskManager.getTaskById(taskId)
            if (task == null) {
                buildJsonObject { put("success", JsonPrimitive(false)); put("error", JsonPrimitive("任务不存在：$taskId")) }
            } else if (!task.agentId.isNullOrBlank() && task.agentId != assistantId.toString()) {
                buildJsonObject { put("success", JsonPrimitive(false)); put("error", JsonPrimitive("该任务不属于当前助手，无权取消")) }
            } else {
                when (val outcome = backgroundTaskManager.cancelTask(taskId)) {
                    is me.rerere.rikkahub.data.backgroundtask.CancelTaskOutcome.Cancelled -> buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("task_id", JsonPrimitive(taskId))
                        put("message", JsonPrimitive("任务已取消，未完成的部分不会生成"))
                    }

                    is me.rerere.rikkahub.data.backgroundtask.CancelTaskOutcome.NotCancellable -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive(outcome.reason))
                    }

                    me.rerere.rikkahub.data.backgroundtask.CancelTaskOutcome.NotFound -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("任务不存在：$taskId"))
                    }
                }
            }
        }
    },
)
