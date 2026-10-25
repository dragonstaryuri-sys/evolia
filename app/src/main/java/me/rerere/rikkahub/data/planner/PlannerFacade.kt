package me.rerere.rikkahub.data.planner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.core.data.db.DbTransactionRunner
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.dao.ScheduleEventDAO
import me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SchedulePlanner 业务门面：日程查询、排程草案、确认后保存、日计划 Markdown 导出。
 *
 * 职责边界：
 * - 待办 CRUD 仍由 ScheduleManager 背后的 ScheduleRepository/ScheduleDAO 负责，
 *   本类只做只读查询，不重复实现待办管理；
 * - 「生成草案」不写库，「保存」在事务内完成并做保存前二次校验（冲突/待办存在性）；
 * - 草案由应用层持有（draftId 一次性消费），用户确认流程不依赖提示词；
 * - Markdown / PDF 为只读渲染，绝不修改真实待办与日程。
 */
class PlannerFacade(
    private val context: Context,
    private val scheduleDAO: ScheduleDAO,
    private val scheduleEventDAO: ScheduleEventDAO,
    private val transactionRunner: DbTransactionRunner,
) {
    private val renderer = PlannerMarkdownPdfRenderer()

    /** 应用层持有的待确认草案：draftId -> 草案（保存成功后消费，防止重复提交） */
    private val pendingDrafts = ConcurrentHashMap<String, PlannerDraft>()

    // ---------------- 日程查询 ----------------

    /**
     * 查询日期区间内的真实日程（按日期、开始时间排序）。
     * 区间最长 [MAX_QUERY_DAYS] 天，防止 AI 拉取全表。
     */
    suspend fun queryEvents(startDateText: String?, endDateText: String?): PlannerQueryResult {
        val today = LocalDate.now()
        val start = PlannerDraftValidator.parseDate(startDateText) ?: today
        val end = PlannerDraftValidator.parseDate(endDateText) ?: start
        val (safeStart, safeEnd) = if (end.isBefore(start)) end to start else start to end
        val clampedEnd = safeStart.plusDays(MAX_QUERY_DAYS - 1L).let { if (safeEnd.isAfter(it)) it else safeEnd }

        val todosById = scheduleDAO.getAllSchedules().first().associateBy { it.id }
        val events = mutableListOf<PlannerEventInfo>()
        var date = safeStart
        while (!date.isAfter(clampedEnd)) {
            val epochDay = date.toEpochDay()
            scheduleEventDAO.getEventsForDate(epochDay).forEach { event ->
                val todo = todosById[event.taskId] ?: return@forEach
                events.add(
                    PlannerEventInfo(
                        eventId = event.id,
                        taskId = event.taskId,
                        taskTitle = todo.title,
                        date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        startMinute = event.startTime,
                        endMinute = event.endTime,
                        completed = todo.isCompleted,
                        category = event.category,
                    )
                )
            }
            date = date.plusDays(1)
        }
        val clamped = safeEnd.isAfter(clampedEnd)
        return PlannerQueryResult(
            startDate = safeStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
            endDate = clampedEnd.format(DateTimeFormatter.ISO_LOCAL_DATE),
            events = events,
            clamped = clamped,
        )
    }

    /** 当前正在进行的日程（今天 && start <= now < end），供上下文注入 */
    suspend fun currentOngoingEvents(now: LocalDateTime = LocalDateTime.now()): List<PlannerEventInfo> {
        val today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowMinute = now.hour * 60 + now.minute
        return queryEvents(today, today).events.filter {
            it.startMinute <= nowMinute && it.endMinute > nowMinute
        }
    }

    /**
     * 已排程待办的 taskId 集合（存在至少一条关联日程）。
     * 排程状态实时推导自 schedule_events，不在待办表冗余存储，
     * 供 schedule_manager 的 list 标注 scheduled 字段与待办 UI 共用同一语义。
     */
    suspend fun scheduledTaskIds(): Set<Long> =
        scheduleEventDAO.observeScheduledTaskIds().first().toSet()

    // ---------------- 排程草案（只读，不写库） ----------------

    /**
     * 接收 AI 提议的排程条目，逐条校验：
     * - 新增条目：待办存在、日期时间合法、结束晚于开始；
     * - 修改条目（带 event_id）：日程存在、不支持更换关联待办；
     * - 时间重叠**允许保存**，但生成 warnings 提示该时段已有的日程，由 AI 转述给用户。
     * 合法条目进入草案（不写入数据库），硬拒绝条目附带原因返回。
     */
    suspend fun createDraft(rawItems: List<PlannerDraftValidator.ItemInput>): PlannerDraftResult {
        if (rawItems.isEmpty()) {
            return PlannerDraftResult(
                success = false, draftId = null, validItems = emptyList(),
                warnings = emptyList(), issues = listOf("草案为空"),
            )
        }
        val todosById = scheduleDAO.getAllSchedules().first().associateBy { it.id }
        val existingByDate = HashMap<LocalDate, List<PlannerEventInfo>>()
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val accepted = mutableListOf<PlannerDraftItem>()
        val acceptedTimes = mutableListOf<PlannerDraftValidator.ParsedTime>()

        // 某日期的现有日程（含待办标题，用于重叠提示）
        suspend fun existingEventsOf(date: LocalDate): List<PlannerEventInfo> =
            existingByDate.getOrPut(date) {
                scheduleEventDAO.getEventsForDate(date.toEpochDay()).mapNotNull { event ->
                    val todo = todosById[event.taskId] ?: return@mapNotNull null
                    PlannerEventInfo(
                        eventId = event.id,
                        taskId = event.taskId,
                        taskTitle = todo.title,
                        date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        startMinute = event.startTime,
                        endMinute = event.endTime,
                        completed = todo.isCompleted,
                        category = event.category,
                    )
                }
            }

        rawItems.forEachIndexed { index, item ->
            val parsed = PlannerDraftValidator.parseTime(item.date, item.startTime, item.endTime)
            if (parsed == null) {
                issues.add("第${index + 1}条：日期或时间格式无效（需要 yyyy-MM-dd 与 HH:mm，且结束晚于开始，结束最晚 23:59）")
                return@forEachIndexed
            }

            val isModify = item.eventId != null
            val todo: me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
            val eventId: Long?
            if (isModify) {
                // 修改已有日程的时间：沿用其关联待办，不支持更换
                val event = scheduleEventDAO.getEventById(item.eventId!!)
                if (event == null) {
                    issues.add("第${index + 1}条：要修改的日程 ${item.eventId} 不存在（可先用 query 查询最新 event_id）")
                    return@forEachIndexed
                }
                val owner = todosById[event.taskId]
                if (owner == null) {
                    issues.add("第${index + 1}条：该日程关联的待办已不存在")
                    return@forEachIndexed
                }
                if (item.taskId != null && item.taskId != event.taskId) {
                    issues.add("第${index + 1}条：修改日程不支持更换关联待办（该日程属于「${owner.title}」），请删除该日程后重新排程")
                    return@forEachIndexed
                }
                todo = owner
                eventId = event.id
            } else {
                val requestedId = item.taskId
                val owner = requestedId?.let { todosById[it] }
                if (owner == null) {
                    issues.add("第${index + 1}条：待办 ${requestedId ?: "(未提供)"} 不存在")
                    return@forEachIndexed
                }
                todo = owner
                eventId = null
            }

            // 时间重叠：允许保存，但提示该时段已有的日程（修改条目排除自身）
            val dateText = parsed.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            existingEventsOf(parsed.date)
                .filter { it.eventId != eventId }
                .filter { parsed.startMinute < it.endMinute && parsed.endMinute > it.startMinute }
                .forEach { clash ->
                    warnings.add(
                        "「${todo.title}」$dateText ${formatMinutes(parsed.startMinute)}-${formatMinutes(parsed.endMinute)} " +
                            "与已有日程「${clash.taskTitle}」${clash.timeLabel()} 时间重叠"
                    )
                }
            // 草案内部条目互相重叠：同样允许并提示
            acceptedTimes
                .filter { it.date == parsed.date && parsed.startMinute < it.endMinute && parsed.endMinute > it.startMinute }
                .forEach { other ->
                    warnings.add(
                        "「${todo.title}」$dateText ${formatMinutes(parsed.startMinute)}-${formatMinutes(parsed.endMinute)} " +
                            "与草案内其他条目（${formatMinutes(other.startMinute)}-${formatMinutes(other.endMinute)}）时间重叠"
                    )
                }

            acceptedTimes.add(parsed)
            accepted.add(
                PlannerDraftItem(
                    eventId = eventId,
                    taskId = todo.id,
                    taskTitle = todo.title,
                    date = dateText,
                    startMinute = parsed.startMinute,
                    endMinute = parsed.endMinute,
                    category = todo.category,
                )
            )
        }

        if (accepted.isEmpty()) {
            return PlannerDraftResult(
                success = false, draftId = null, validItems = emptyList(),
                warnings = warnings, issues = issues,
            )
        }
        val draftId = UUID.randomUUID().toString().take(8)
        pendingDrafts[draftId] = PlannerDraft(
            draftId = draftId,
            items = accepted,
            createdAt = System.currentTimeMillis(),
        )
        evictStaleDrafts()
        return PlannerDraftResult(
            success = true, draftId = draftId, validItems = accepted,
            warnings = warnings, issues = issues,
        )
    }

    /**
     * 保存用户确认后的草案（draftId 一次性消费）。
     * 保存前重新查询：待办仍存在、要修改的日程仍存在；时间重叠不再拦截，
     * 而是基于保存时的最新日程状态生成 warnings（AI 应转述给用户）。
     * 硬错误条目（待办/日程消失）被跳过并记入 errors，其余在单事务内落库。
     */
    suspend fun saveDraft(draftId: String): PlannerSaveResult {
        val draft = pendingDrafts[draftId]
            ?: return PlannerSaveResult(
                success = false, saved = emptyList(), warnings = emptyList(), errors = emptyList(),
                error = "草案 $draftId 不存在或已保存（重复提交）",
            )

        val todosById = scheduleDAO.getAllSchedules().first().associateBy { it.id }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val ops = mutableListOf<PlannerDraftItem>()

        draft.items.forEach { item ->
            val todo = todosById[item.taskId]
            if (todo == null) {
                errors.add("「${item.taskTitle}」(${item.taskId}) 已不存在，可能已被删除，该条目已跳过")
                return@forEach
            }
            if (item.eventId != null) {
                val event = scheduleEventDAO.getEventById(item.eventId)
                if (event == null) {
                    errors.add("「${item.taskTitle}」的原日程已被删除，无法修改时间，该条目已跳过")
                    return@forEach
                }
            }
            // 基于当前数据库的时间重叠提示（修改条目排除自身）
            val existing = scheduleEventDAO.getEventsForDate(LocalDate.parse(item.date).toEpochDay())
                .filter { it.id != item.eventId }
                .filter { item.startMinute < it.endTime && item.endMinute > it.startTime }
            existing.forEach { clash ->
                val clashTitle = todosById[clash.taskId]?.title ?: "待办 ${clash.taskId}"
                warnings.add(
                    "「${item.taskTitle}」${item.date} ${formatMinutes(item.startMinute)}-${formatMinutes(item.endMinute)} " +
                        "与已有日程「$clashTitle」${formatMinutes(clash.startTime)}-${formatMinutes(clash.endTime)} 时间重叠"
                )
            }
            ops.add(item)
        }

        if (ops.isEmpty()) {
            pendingDrafts.remove(draftId)
            return PlannerSaveResult(
                success = false, saved = emptyList(),
                warnings = warnings, errors = errors,
                error = "草案中没有可保存的条目",
            )
        }

        val saved = transactionRunner.runInTransaction {
            ops.mapNotNull { item ->
                val event = if (item.eventId != null) {
                    // 修改：更新已有日程的时间与类别，不触碰待办本身
                    val existing = scheduleEventDAO.getEventById(item.eventId) ?: return@mapNotNull null
                    existing.copy(
                        date = LocalDate.parse(item.date).toEpochDay(),
                        startTime = item.startMinute,
                        endTime = item.endMinute,
                        category = item.category,
                        updatedAt = System.currentTimeMillis(),
                    ).also { scheduleEventDAO.updateEvent(it) }
                    item.eventId
                } else {
                    scheduleEventDAO.insertEvent(
                        ScheduleEventEntity(
                            taskId = item.taskId,
                            date = LocalDate.parse(item.date).toEpochDay(),
                            startTime = item.startMinute,
                            endTime = item.endMinute,
                            category = item.category,
                        )
                    )
                }
                PlannerSaveResult.SavedEventInfo(
                    eventId = event,
                    taskId = item.taskId,
                    taskTitle = item.taskTitle,
                    date = item.date,
                    startMinute = item.startMinute,
                    endMinute = item.endMinute,
                )
            }
        }
        // 一次性消费，防止重复提交
        pendingDrafts.remove(draftId)
        return PlannerSaveResult(
            success = saved.isNotEmpty(),
            saved = saved,
            warnings = warnings,
            errors = errors,
            error = if (saved.isEmpty()) "所有条目均未能保存" else null,
        )
    }

    // ---------------- 日计划 Markdown 导出 ----------------

    /**
     * 把 AI 生成的日计划 Markdown 转换为 PDF 并导出到公共下载目录。
     * 纸张支持 A4 / 58mm / 80mm；热敏纸强制纵向（orientation 参数仅对 A4 生效）。
     */
    suspend fun exportMarkdownPdf(
        markdown: String,
        paper: PlannerPaper,
        landscape: Boolean,
    ): PlannerExportResult = withContext(Dispatchers.Default) {
        if (markdown.isBlank()) {
            return@withContext PlannerExportResult(
                success = false, pages = 0, pdfFile = null, previewFile = null,
                publicDisplayName = null, publicUri = null, error = "Markdown 内容为空",
            )
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val privateFile = File(File(context.filesDir, PDF_DIR), "Evolia日计划_$timestamp.pdf")

        val pages = withContext(Dispatchers.IO) {
            PlannerDocumentExporter.exportPdf(renderer, markdown, paper, landscape, privateFile)
        }
        val preview = withContext(Dispatchers.IO) {
            PlannerDocumentExporter.renderPreviewPng(renderer, markdown, paper, landscape, File(context.filesDir, PREVIEW_DIR))
        }
        val exported = withContext(Dispatchers.IO) { exportToPublicDownloads(privateFile, timestamp) }
        PlannerExportResult(
            success = true,
            pages = pages,
            pdfFile = privateFile,
            previewFile = preview,
            publicDisplayName = exported?.first,
            publicUri = exported?.second,
            error = null,
        )
    }

    // ---------------- 内部工具 ----------------

    private fun exportToPublicDownloads(source: File, timestamp: String): Pair<String, Uri>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val displayName = "Evolia日计划_$timestamp.pdf"
            val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/Evolia/plans"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            val output = context.contentResolver.openOutputStream(uri)
            if (output == null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            }
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            displayName to uri
        }.getOrNull()
    }

    private fun evictStaleDrafts() {
        if (pendingDrafts.size <= MAX_PENDING_DRAFTS) return
        pendingDrafts.entries
            .sortedBy { it.value.createdAt }
            .take(pendingDrafts.size - MAX_PENDING_DRAFTS)
            .forEach { pendingDrafts.remove(it.key) }
    }

    private fun formatMinutes(minute: Int): String {
        val safe = minute.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    companion object {
        private const val PREVIEW_DIR = "planner_previews"
        private const val PDF_DIR = "planner_exports"
        private const val MAX_QUERY_DAYS = 31L
        private const val MAX_PENDING_DRAFTS = 20
    }
}

/** 日程查询结果 */
data class PlannerQueryResult(
    val startDate: String,
    val endDate: String,
    val events: List<PlannerEventInfo>,
    val clamped: Boolean,
)
