package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.planner.PlannerDraftValidator
import me.rerere.rikkahub.data.planner.PlannerFacade
import me.rerere.rikkahub.data.planner.PlannerPaper

/** schedule_planner 的系统级使用规范（排程流程、时间规则与 Markdown 语法边界） */
private const val SCHEDULE_PLANNER_SYSTEM_PROMPT = """schedule_planner 使用规范：
1. query 返回的才是真实日程（字段含 event_id/task_id/待办名/日期/开始/结束/完成状态）。对话中提到但查询不到的安排，不得当作已存在的日程。
2. 排程准备：待办清单用 schedule_manager(action=list, category=user) 获取（含重要度/紧急度/难度/截止时间/scheduled 已排程标记）。排程只引用真实存在的待办 ID；同一待办可以被安排多次，不要因为已安排过一次就排除它。
3. 预计耗时参考：难度0（简单）约 15 分钟、难度1（一般）约 30 分钟、难度2（不简单）约 60 分钟；用户有明确要求时按用户要求，无法判断时按一般 30 分钟。
4. draft 支持两种条目，可任意混合：
   - 新增日程：{task_id, date, start_time, end_time}；可以只提交一个时段，不必排满全天。
   - 修改已有日程的时间：{event_id, date, start_time, end_time}（event_id 来自 query；不支持更换关联待办）。
   draft 只校验不写库，返回 draft_id；格式非法、待办/日程不存在的条目会被拒绝并附 issues。
5. 时间重叠是允许的：draft/save 的 warnings 会列出该时段已有的日程（含名称与时间），请原样转述给用户，由用户决定是否保留；不要因为重叠就拒绝执行用户的要求。
6. 把草案展示给用户并等待明确同意后才能调用 save。save 基于保存时的最新日程重新生成 warnings；draft_id 保存成功后失效（防重复提交）。保存只写入日程时间，不会修改待办完成状态或截止时间。
7. 时间格式 HH:mm；结束必须晚于开始且最晚 23:59。
8. 日计划导出（export）：markdown 必须基于真实数据撰写，明确区分"已保存日程"与"未保存草案"；可用语法：# 标题、段落、- 无序列表、1. 有序列表、- [x]/- [ ] 任务复选框、> 引用、--- 分隔线、**加粗**；不支持表格与图片（请用列表结构，窄纸张下尤其如此）。纸张 paper=a4（可加 orientation=landscape）或 58mm、80mm；58/80mm 为热敏连续纸，PDF 按内容实际高度生成单页（无页码），适合小票打印，内容应短行、精炼。
9. export 结果中的预览图 markdown_link 请放进回复供用户确认版面，并告知 PDF 文件位置。导出是快照，不会修改任何日程或待办。"""

private fun formatPlannerMinute(minute: Int): String {
    val safe = minute.coerceIn(0, 24 * 60 - 1)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

/**
 * SchedulePlanner 工具：日程查询 / 排程草案 / 确认保存 / 日计划 Markdown 导出 PDF。
 * 与 schedule_manager（待办 CRUD）职责分离，同受「日程管理」工具权限门控。
 */
internal fun getSchedulePlannerTool(plannerFacade: PlannerFacade): Tool = Tool(

    name = "schedule_planner",
    description = "日程规划助手：查询用户的真实日程安排、提交排程草案、在用户确认后保存排程、并把日计划导出为 PDF（支持 A4 / 58mm / 80mm 热敏纸）。" +
        "与 schedule_manager 的分工：schedule_manager 负责待办 CRUD（增删改查待办本身），schedule_planner 负责时间安排与日计划文件。" +
        "排程流程：先用 schedule_manager(action=list) 获取未完成待办、再用本工具 action=query 查询已有日程，然后 action=draft 提交草案给用户确认，用户同意后 action=save 保存。未经用户确认不得调用 save。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "query=查询日程；draft=提交排程草案（只校验不写库，返回 draft_id）；save=保存已确认的草案；export=把 Markdown 日计划导出为 PDF")
                    put("enum", JsonArray(listOf(
                        JsonPrimitive("query"), JsonPrimitive("draft"), JsonPrimitive("save"), JsonPrimitive("export")
                    )))
                })
                put("start_date", buildJsonObject {
                    put("type", "string")
                    put("description", "query：起始日期 yyyy-MM-dd，缺省为今天")
                })
                put("end_date", buildJsonObject {
                    put("type", "string")
                    put("description", "query：结束日期 yyyy-MM-dd（含），缺省为起始日期；最长查询 31 天")
                })
                put("items", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "draft：排程条目数组。新增日程每项 {task_id, date(yyyy-MM-dd), start_time(HH:mm), end_time(HH:mm)}；" +
                            "修改已有日程的时间每项 {event_id, date, start_time, end_time}（event_id 来自 query，不支持更换关联待办）"
                    )
                })
                put("draft_id", buildJsonObject {
                    put("type", "string")
                    put("description", "save：用户确认的草案 ID（来自 draft 返回值，保存成功后失效）")
                })
                put("markdown", buildJsonObject {
                    put("type", "string")
                    put("description", "export：日计划 Markdown 全文，必须基于 query 到的真实日程/待办数据撰写")
                })
                put("paper", buildJsonObject {
                    put("type", "string")
                    put("description", "export：纸张尺寸 a4 / 58mm / 80mm（热敏小票纸）")
                })
                put("orientation", buildJsonObject {
                    put("type", "string")
                    put("description", "export：portrait / landscape，仅对 a4 生效，热敏纸固定纵向")
                })
            },
            required = listOf("action")
        )
    },
    systemPrompt = { _, _ ->
        SCHEDULE_PLANNER_SYSTEM_PROMPT
    },
    execute = {
        val json = it.jsonObject
        val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
        try {
            when (action) {
                "query" -> {
                    val result = plannerFacade.queryEvents(
                        startDateText = json["start_date"]?.jsonPrimitive?.contentOrNull,
                        endDateText = json["end_date"]?.jsonPrimitive?.contentOrNull,
                    )
                    val events = result.events.map { event ->
                        buildJsonObject {
                            put("event_id", event.eventId)
                            put("task_id", event.taskId)
                            put("title", event.taskTitle)
                            put("date", event.date)
                            put("start_time", formatPlannerMinute(event.startMinute))
                            put("end_time", formatPlannerMinute(event.endMinute))
                            put("is_completed", event.completed)
                        }
                    }
                    buildJsonObject {
                        put("success", true)
                        put("date_range", "${result.startDate} ~ ${result.endDate}")
                        put("events", JsonArray(events))
                        if (result.clamped) put("note", "查询区间超过 31 天，已截断")
                        if (result.events.isEmpty()) put("note", "该区间没有日程安排")
                    }
                }

                "draft" -> {
                    val itemsJson = json["items"] as? JsonArray
                    if (itemsJson == null) {
                        buildJsonObject { put("success", false); put("error", "缺少 items 数组") }
                    } else {
                        val inputs = itemsJson.mapNotNull { element ->
                            val obj = element as? JsonObject ?: return@mapNotNull null
                            PlannerDraftValidator.ItemInput(
                                taskId = obj["task_id"]?.jsonPrimitive?.longOrNull,
                                eventId = obj["event_id"]?.jsonPrimitive?.longOrNull,
                                date = obj["date"]?.jsonPrimitive?.contentOrNull,
                                startTime = obj["start_time"]?.jsonPrimitive?.contentOrNull,
                                endTime = obj["end_time"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                        val result = plannerFacade.createDraft(inputs)
                        buildJsonObject {
                            put("success", result.success)
                            result.draftId?.let { put("draft_id", it) }
                            put("accepted", JsonArray(result.validItems.map { item ->
                                buildJsonObject {
                                    item.eventId?.let { put("event_id", it) }
                                    put("task_id", item.taskId)
                                    put("title", item.taskTitle)
                                    put("date", item.date)
                                    put("start_time", formatPlannerMinute(item.startMinute))
                                    put("end_time", formatPlannerMinute(item.endMinute))
                                }
                            }))
                            if (result.warnings.isNotEmpty()) {
                                put("warnings", JsonArray(result.warnings.map { JsonPrimitive(it) }))
                            }
                            if (result.issues.isNotEmpty()) {
                                put("issues", JsonArray(result.issues.map { JsonPrimitive(it) }))
                            }
                            put(
                                "note",
                                if (result.success) {
                                    "草案已生成但尚未写入数据库：请把 accepted 条目展示给用户并等待明确同意，用户同意后才能用 draft_id 调用 save。" +
                                        "有 warnings 时请转述时间重叠情况（重叠允许保存，由用户决定）；有 issues 时说明哪些条目被拒绝及原因。"
                                } else {
                                    "草案全部条目被拒绝，请根据 issues 调整后重试。"
                                }
                            )
                        }
                    }
                }

                "save" -> {
                    val draftId = json["draft_id"]?.jsonPrimitive?.contentOrNull
                    if (draftId.isNullOrBlank()) {
                        buildJsonObject {
                            put("success", false)
                            put("error", "缺少 draft_id（必须先 draft 生成并经用户确认）")
                        }
                    } else {
                        val result = plannerFacade.saveDraft(draftId)
                        buildJsonObject {
                            put("success", result.success)
                            if (result.saved.isNotEmpty()) {
                                put("saved", JsonArray(result.saved.map { item ->
                                    buildJsonObject {
                                        put("event_id", item.eventId)
                                        put("task_id", item.taskId)
                                        put("title", item.taskTitle)
                                        put("date", item.date)
                                        put("start_time", formatPlannerMinute(item.startMinute))
                                        put("end_time", formatPlannerMinute(item.endMinute))
                                    }
                                }))
                            }
                            if (result.warnings.isNotEmpty()) {
                                put("warnings", JsonArray(result.warnings.map { JsonPrimitive(it) }))
                            }
                            if (result.errors.isNotEmpty()) {
                                put("errors", JsonArray(result.errors.map { JsonPrimitive(it) }))
                            }
                            result.error?.let { put("error", it) }
                            put(
                                "note",
                                when {
                                    result.success && result.warnings.isEmpty() ->
                                        "排程已保存到用户日程，请告知用户。保存不会修改待办完成状态。"
                                    result.success ->
                                        "排程已保存，但存在时间重叠（见 warnings），请明确转述给用户，由用户决定是否调整。"
                                    else ->
                                        "没有条目被保存：errors 说明各条目被跳过的原因（如待办或日程已被删除），请向用户说明并重新规划。"
                                }
                            )
                        }
                    }
                }

                "export" -> {
                    val markdown = json["markdown"]?.jsonPrimitive?.contentOrNull
                    if (markdown.isNullOrBlank()) {
                        buildJsonObject { put("success", false); put("error", "缺少 markdown 内容") }
                    } else {
                        val paperText = json["paper"]?.jsonPrimitive?.contentOrNull ?: "a4"
                        val paper = when (paperText.lowercase()) {
                            "58", "58mm" -> PlannerPaper.PAPER_58MM
                            "80", "80mm" -> PlannerPaper.PAPER_80MM
                            else -> PlannerPaper.A4
                        }
                        val landscape = json["orientation"]?.jsonPrimitive?.contentOrNull?.lowercase() == "landscape"
                        val result = plannerFacade.exportMarkdownPdf(markdown, paper, landscape)
                        if (!result.success) {
                            buildJsonObject { put("success", false); put("error", result.error ?: "导出失败") }
                        } else {
                            buildJsonObject {
                                put("success", true)
                                put("pages", result.pages)
                                put("paper", paper.displayName)
                                result.previewFile?.let {
                                    put("markdown_link", "![日计划预览](${android.net.Uri.fromFile(it)})")
                                }
                                result.publicUri?.let { put("public_uri", it.toString()) }
                                result.publicDisplayName?.let {
                                    put("public_path", "Download/Evolia/plans/$it")
                                }
                                put(
                                    "note",
                                    "PDF 已生成。请把 markdown_link 放进回复供用户确认纸张版面，并告知文件位置" +
                                        (result.publicDisplayName?.let { "（Download/Evolia/plans/$it）" }
                                            ?: "（应用私有目录，当前系统不支持写入公共下载目录）") +
                                        "。导出不会修改任何日程或待办。"
                                )
                            }
                        }
                    }
                }

                else -> buildJsonObject { put("error", "未知操作：$action（支持 query / draft / save / export）") }
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalTools", "schedule_planner failed", e)
            buildJsonObject {
                put("success", false)
                put("error", e.message ?: "日程规划操作失败")
                put("note", "操作失败不会影响用户的日程与待办数据")
            }
        }
    }
)
