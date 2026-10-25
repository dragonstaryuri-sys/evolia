package me.rerere.rikkahub.data.planner

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 排程草案的系统侧校验（纯 Kotlin，可 JVM 单测）：
 * - 待办存在性由调用方查询后传入；
 * - 日期 / 时间格式、结束晚于开始、与现有日程及草案内部的时间冲突；
 * - 只做校验与规范化，不产生任何数据库写入。
 */
object PlannerDraftValidator {

    /** 单条草案输入（AI 原始值，全部为字符串）。
     * eventId 非空 = 修改已有日程的时间（此时 taskId 可省略，沿用原日程的待办）；
     * eventId 为空 = 新增日程（taskId 必填）。
     */
    data class ItemInput(
        val taskId: Long? = null,
        val eventId: Long? = null,
        val date: String? = null,
        val startTime: String? = null,
        val endTime: String? = null,
    )

    data class ParsedTime(val date: LocalDate, val startMinute: Int, val endMinute: Int)


    /** 解析并校验单个时间字段，非法返回 null 并给出原因 */
    fun parseTime(dateText: String?, startText: String?, endText: String?): ParsedTime? {
        val date = parseDate(dateText) ?: return null
        val start = parseMinute(startText) ?: return null
        val end = parseMinute(endText) ?: return null
        if (end <= start) return null
        return ParsedTime(date, start, end)
    }

    fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    /** 解析 HH:mm 为当天分钟数；结束时间最晚 23:59（与现有日程存储一致，不支持跨天） */
    fun parseMinute(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
        val (h, m) = match.destructured
        val hour = h.toInt()
        val minute = m.toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
