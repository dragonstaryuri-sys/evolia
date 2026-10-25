package me.rerere.rikkahub.data.planner

import java.io.File

/**
 * SchedulePlanner 数据模型：
 * - 日程查询结果 / 排程草案 / 保存与导出结果；
 * - 日程数据沿用现有 `schedule_events` 表（id/task_id/date/start_time/end_time），
 *   不新增同义数据表。
 */

/** 一条真实日程（关联待办信息已join） */
data class PlannerEventInfo(
    val eventId: Long,
    val taskId: Long,
    val taskTitle: String,
    val date: String,        // yyyy-MM-dd
    val startMinute: Int,    // 当天分钟数
    val endMinute: Int,
    val completed: Boolean,
    val category: String,
) {
    fun timeLabel(): String = "${formatMinute(startMinute)} - ${formatMinute(endMinute)}"
}

/** 排程草案条目（AI 提议，未写入数据库）。
 * eventId 为 null 表示新增日程；非 null 表示把已有日程调整为新时间（不更换关联待办）。
 */
data class PlannerDraftItem(
    val eventId: Long?,
    val taskId: Long,
    val taskTitle: String,
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val category: String,
)

/** 应用层持有的排程草案（生成草案与保存排程分离的关键） */
data class PlannerDraft(
    val draftId: String,
    val items: List<PlannerDraftItem>,
    val createdAt: Long,
)

/** 草案校验结果：合法条目 + 冲突提示（允许保存）+ 被拒绝条目的问题说明 */
data class PlannerDraftResult(
    val success: Boolean,
    val draftId: String?,
    val validItems: List<PlannerDraftItem>,
    /** 时间重叠提示：重叠允许保存，但应转述给用户 */
    val warnings: List<String>,
    /** 硬拒绝条目的原因（格式非法、待办/日程不存在等） */
    val issues: List<String>,
)

/** 草案保存结果 */
data class PlannerSaveResult(
    /** 至少保存了一条即为成功；errors 中的条目被跳过 */
    val success: Boolean,
    val saved: List<SavedEventInfo>,
    /** 时间重叠提示（基于保存时的最新日程状态） */
    val warnings: List<String>,
    /** 被跳过条目的原因（待办不存在、日程已被删除等） */
    val errors: List<String>,
    /** 整体失败原因（草案不存在/已消费、内容为空等） */
    val error: String?,
) {
    data class SavedEventInfo(
        val eventId: Long,
        val taskId: Long,
        val taskTitle: String,
        val date: String,
        val startMinute: Int,
        val endMinute: Int,
    )
}

/** 纸张尺寸：A4 打印与 58mm / 80mm 热敏小票纸。
 * 热敏纸为连续纸（continuous=true）：PDF 按内容实际高度生成单页，不做固定高度分页。
 */
enum class PlannerPaper(
    val displayName: String,
    val widthPt: Float,     // 1pt = 1/72 inch
    val heightPt: Float,    // 连续纸下仅为分页兜底高度（内容超高时使用）
    val marginPt: Float,
    val bodyFontPt: Float,
    val continuous: Boolean,
) {
    A4("A4", 595f, 842f, 40f, 10.5f, continuous = false),
    PAPER_58MM("58mm热敏纸", 164.4f, 842f, 10f, 8f, continuous = true),
    PAPER_80MM("80mm热敏纸", 226.8f, 842f, 12f, 9f, continuous = true),
}

/** Markdown 日计划导出结果 */
data class PlannerExportResult(
    val success: Boolean,
    val pages: Int,
    val pdfFile: File?,
    val previewFile: File?,
    val publicDisplayName: String?,
    val publicUri: android.net.Uri?,
    val error: String?,
)

private fun formatMinute(minute: Int): String {
    val safe = minute.coerceIn(0, 24 * 60 - 1)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
