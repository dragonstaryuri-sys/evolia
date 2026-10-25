package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.print.CreatePrintTaskResult
import me.rerere.rikkahub.data.print.PrintTask
import me.rerere.rikkahub.data.print.PrintTaskManager
import me.rerere.rikkahub.data.print.PrintTaskStatus

/** print_content 的系统级使用规范（内容语法边界与确认流程行为） */
private const val PRINT_CONTENT_SYSTEM_PROMPT = """print_content 使用规范：
1. 用途：把 Markdown 内容打印到用户配置的热敏打印机（PD01，58mm 打印宽度）。适合日程、待办、笔记、摘要、清单、短文章等。
2. content 语法边界：# 标题、段落、- 无序列表、1. 有序列表、- [x]/- [ ] 任务复选框、> 引用、--- 分隔线、**加粗**；不支持表格与图片，请改用列表结构（窄纸张下用短行、精炼表达）。
3. title 为可选的打印任务标题，建议简短概括内容主题。
4. 确认流程：默认每次打印需要用户确认。action=print 后若返回 status=awaiting_confirmation，说明任务已创建并在聊天中展示打印预览卡片，用户会在卡片上确认或取消；此时你只能告知"已准备好打印、等待用户确认"，不得声称已经打印成功。
5. 若返回 status=executing（用户已开启自动打印），说明任务已开始执行，可告知用户正在打印；出纸需要数秒到数十秒。
6. 若返回 error 提示未配置打印机，请引导用户到 设置 → 扩展服务 → 打印机服务 添加设备；不要重复调用本工具。
7. 不要为同一内容重复创建打印任务；用户明确要求重打时才再次调用。
8. 状态查询（action=status + task_id）：返回任务实时状态。completed 仅代表打印数据已全部发送，不代表已确认物理出纸；awaiting_confirmation 表示等待用户在卡片上确认，请提醒用户查看卡片。
9. 失败重试（action=retry + task_id）：仅当任务 failed 且 sent_rows=0（尚未打印任何内容）时允许，重试沿用原任务不会重复出纸；outcome_unknown（结果未知，可能已部分出纸）的任务禁止重试或重打，必须由用户检查纸张后决定。"""

/** 任务状态 → AI 可读说明 */
private fun statusText(status: PrintTaskStatus): String = when (status) {
    PrintTaskStatus.PREPARING -> "preparing（准备中）"
    PrintTaskStatus.AWAITING_CONFIRMATION -> "awaiting_confirmation（等待用户在卡片上确认）"
    PrintTaskStatus.CONNECTING -> "connecting（正在连接打印机）"
    PrintTaskStatus.SENDING -> "sending（正在发送打印数据）"
    PrintTaskStatus.COMPLETED -> "completed（打印数据已全部发送，物理出纸未经协议确认）"
    PrintTaskStatus.FAILED -> "failed（已失败）"
    PrintTaskStatus.CANCELLED -> "cancelled（已取消）"
    PrintTaskStatus.OUTCOME_UNKNOWN -> "outcome_unknown（结果未知，可能已部分出纸）"
}

private fun buildTaskStatusJson(task: PrintTask): JsonObject {
    val record = task.record
    return buildJsonObject {
        put("success", true)
        put("task_id", record.taskId)
        put("title", record.title)
        put("status", record.status.name.lowercase())
        put("status_text", statusText(record.status))
        put("sent_rows", record.sentRows)
        put("total_rows", record.rowCount)
        record.errorMessage?.let { put("error", it) }
        put(
            "note",
            when {
                record.status == PrintTaskStatus.AWAITING_CONFIRMATION ->
                    "任务等待用户在聊天卡片上确认，请提醒用户查看并确认，不要代替用户声称已打印。"
                record.status == PrintTaskStatus.COMPLETED ->
                    "数据已全部发送即视为完成；物理出纸需数秒，请勿断言打印机已出纸完成。"
                record.status == PrintTaskStatus.FAILED && record.sentRows == 0 ->
                    "尚未打印任何内容，可用 action=retry 重试；请先把失败原因转述给用户。"
                record.status == PrintTaskStatus.OUTCOME_UNKNOWN ->
                    "可能已部分出纸，禁止自动重试或重打，请让用户检查纸张后明确决定。"
                else -> "任务执行中，可用 action=status 查询进度（建议间隔 10 秒以上，不要密集查询）。"
            }
        )
    }
}

/**
 * print_content：通用 AI 打印工具。
 * - action=print：创建打印任务后立即返回（不阻塞等待用户确认），执行与状态由
 *   PrintTaskManager 管理，聊天中的任务卡片负责展示与确认；
 * - action=status：查询任务实时状态（含进度与失败原因）；
 * - action=retry：重试"零出纸"的失败任务（沿用原任务，不会重复出纸）。
 */
internal fun getPrintContentTool(printTaskManager: PrintTaskManager): Tool = Tool(
    name = "print_content",
    description = "把 Markdown 内容打印到用户的热敏打印机（PD01）。" +
        "默认每次打印需要用户在聊天卡片上确认；用户开启自动打印后直接执行。" +
        "支持 action=status 查询打印任务实时状态、action=retry 重试尚未出纸的失败任务。" +
        "未配置打印机时返回错误，需引导用户先在打印机服务页面添加设备。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "print=创建打印任务（默认）；status=查询任务实时状态；retry=重试零出纸的失败任务"
                    )
                    put("enum", JsonArray(listOf(
                        JsonPrimitive("print"), JsonPrimitive("status"), JsonPrimitive("retry")
                    )))
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "print：要打印的 Markdown 内容。支持标题/段落/有序无序列表/任务复选框/引用/分隔线/加粗；" +
                            "不支持表格与图片（请用列表）。内容应为面向纸张阅读的最终排版。"
                    )
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "print：可选，打印任务标题（用于任务卡片与历史记录）")
                })
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "status/retry：目标打印任务 ID（来自 print 返回值）")
                })
            },
            required = listOf("action")
        )
    },
    systemPrompt = { _, _ ->
        PRINT_CONTENT_SYSTEM_PROMPT
    },
    execute = {
        val json = it.jsonObject
        val action = json["action"]?.jsonPrimitive?.contentOrNull ?: "print"
        try {
            when (action) {
                "status" -> queryPrintStatus(printTaskManager, json)
                "retry" -> retryPrintTask(printTaskManager, json)
                else -> createPrintTask(printTaskManager, json)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("success", false)
                put("error", "打印工具执行失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
)

private fun queryPrintStatus(
    printTaskManager: PrintTaskManager,
    json: JsonObject,
): JsonObject {
    val taskId = json["task_id"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
    if (taskId.isEmpty()) {
        return buildJsonObject {
            put("success", false)
            put("error", "status 查询需要提供 task_id")
        }
    }
    val task = printTaskManager.tasks.value.find { it.record.taskId == taskId }
    return if (task == null) {
        buildJsonObject {
            put("success", false)
            put("error", "任务不存在或已被清理，请确认 task_id；必要时可重新 action=print 创建任务")
        }
    } else {
        buildTaskStatusJson(task)
    }
}

private suspend fun retryPrintTask(
    printTaskManager: PrintTaskManager,
    json: JsonObject,
): JsonObject {
    val taskId = json["task_id"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
    if (taskId.isEmpty()) {
        return buildJsonObject {
            put("success", false)
            put("error", "retry 需要提供 task_id")
        }
    }
    val task = printTaskManager.tasks.value.find { it.record.taskId == taskId }
    if (task == null) {
        return buildJsonObject {
            put("success", false)
            put("error", "任务不存在或已被清理，请重新 action=print 创建任务")
        }
    }
    val record = task.record
    val rejectReason = when {
        record.status != PrintTaskStatus.FAILED ->
            "仅失败任务可重试（当前状态：${statusText(record.status)}）"
        record.sentRows > 0 ->
            "该任务已发送部分数据（${record.sentRows}/${record.rowCount} 行），可能已部分出纸，不能自动重试；请让用户检查纸张后决定是否重新打印"
        record.content.isNullOrBlank() ->
            "任务内容已随历史清理（应用重启后失败任务不再支持自动重试），请重新 action=print 创建任务"
        else -> null
    }
    if (rejectReason != null) {
        return buildJsonObject {
            put("success", false)
            put("error", rejectReason)
        }
    }
    val retried = printTaskManager.retry(taskId)
    return if (retried) {
        buildJsonObject {
            put("success", true)
            put("task_id", taskId)
            put("status", "executing")
            put("message", "已重新开始打印，沿用原任务内容，不会重复出纸")
            put("note", "请告知用户正在重试打印；结果可用 action=status 查询。")
        }
    } else {
        buildJsonObject {
            put("success", false)
            put("error", "重试未执行（任务状态已变化），可用 action=status 查询最新状态")
        }
    }
}

private suspend fun createPrintTask(
    printTaskManager: PrintTaskManager,
    json: JsonObject,
): JsonObject {
    val content = json["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
    if (content.isEmpty()) {
        return buildJsonObject {
            put("success", false)
            put("error", "content 不能为空")
        }
    }
    val title = json["title"]?.jsonPrimitive?.contentOrNull ?: ""
    return when (val result = printTaskManager.createPrintTask(title = title, markdown = content)) {
        is CreatePrintTaskResult.Created -> {
            val status = if (result.autoPrint) "executing" else "awaiting_confirmation"
            buildJsonObject {
                put("success", true)
                put("task_id", result.taskId)
                put("status", status)
                put(
                    "message",
                    if (result.autoPrint) "打印任务已创建并开始执行，请稍候出纸"
                    else "打印任务已创建，等待用户在聊天卡片上确认"
                )
                put(
                    "note",
                    if (result.autoPrint) {
                        "任务已开始执行，可告知用户正在打印；出纸需要数秒到数十秒，可用 action=status 查询进度。"
                    } else {
                        "请告知用户打印已就绪、等待其在任务卡片上点击确认；用户未确认前不得声称已打印。"
                    }
                )
            }
        }
        is CreatePrintTaskResult.Rejected -> {
            buildJsonObject {
                put("success", false)
                put("error", result.message)
            }
        }
    }
}
