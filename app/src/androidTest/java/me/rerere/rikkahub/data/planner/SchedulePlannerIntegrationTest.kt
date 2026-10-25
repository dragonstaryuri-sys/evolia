package me.rerere.rikkahub.data.planner

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.dao.ScheduleEventDAO
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.data.db.RoomTransactionRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * SchedulePlanner 集成测试（instrumented，内存 Room 数据库）：
 * 1. 日程查询（排序 / 区间截断）
 * 2. 排程草案校验（待办不存在 / 时间非法 / 冲突）与「草案不写库」
 * 3. 保存：二次校验、事务写入、draftId 一次性消费（防重复提交）
 * 4. 同一待办多次排程
 * 5. Markdown → PDF 三种纸张（A4 / 58mm / 80mm）渲染冒烟 + 分页
 */
@RunWith(AndroidJUnit4::class)
class SchedulePlannerIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduleDAO: ScheduleDAO
    private lateinit var scheduleEventDAO: ScheduleEventDAO
    private lateinit var facade: PlannerFacade
    private lateinit var outDir: File

    private val today = LocalDate.of(2026, 10, 25)
    private val todayText = "2026-10-25"

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduleDAO = db.scheduleDao()
        scheduleEventDAO = db.scheduleEventDao()
        facade = PlannerFacade(
            context = context,
            scheduleDAO = scheduleDAO,
            scheduleEventDAO = scheduleEventDAO,
            transactionRunner = RoomTransactionRunner(db),
        )
        outDir = File(context.filesDir, "planner_test").apply { deleteRecursively(); mkdirs() }
        runBlocking {
            // 三个待办：难度 0/1/2
            scheduleDAO.insertSchedule(todo("学习 Kotlin", difficulty = 0))
            scheduleDAO.insertSchedule(todo("写周报", difficulty = 1))
            scheduleDAO.insertSchedule(todo("重构导出模块", difficulty = 2))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun todo(title: String, difficulty: Int) = ScheduleEntity(
        title = title,
        content = "",
        priority = 1,
        urgency = 1,
        difficulty = difficulty,
        startTime = System.currentTimeMillis(),
        category = "user",
    )

    // ---------------- 查询 ----------------

    @Test
    fun queryReturnsSortedEventsAndHandlesEmptyRange() = runBlocking {
        // 写入两条日程（故意乱序插入）
        scheduleEventDAO.insertEvent(event(taskId = 2, date = today, start = 660, end = 720))
        scheduleEventDAO.insertEvent(event(taskId = 1, date = today, start = 540, end = 600))

        val result = facade.queryEvents(todayText, todayText)
        assertEquals(2, result.events.size)
        assertEquals(540, result.events[0].startMinute) // 按开始时间升序
        assertEquals(660, result.events[1].startMinute)
        assertEquals("写周报", result.events[1].taskTitle)

        val empty = facade.queryEvents("2026-12-01", "2026-12-02")
        assertTrue(empty.events.isEmpty())
    }

    @Test
    fun queryClampsOversizedRange() = runBlocking {
        val result = facade.queryEvents("2026-01-01", "2026-12-31")
        assertTrue(result.clamped)
        val start = LocalDate.parse(result.startDate)
        val end = LocalDate.parse(result.endDate)
        assertEquals(30L, java.time.temporal.ChronoUnit.DAYS.between(start, end))
    }

    // ---------------- 草案与保存 ----------------

    @Test
    fun draftRejectsInvalidItemsWithoutWritingDatabase() = runBlocking {
        val result = facade.createDraft(
            listOf(
                PlannerDraftValidator.ItemInput(taskId = 999, date = todayText, startTime = "09:00", endTime = "10:00"), // 待办不存在
                PlannerDraftValidator.ItemInput(taskId = 1, date = "2026/10/25", startTime = "09:00", endTime = "10:00"), // 日期格式
                PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "10:00", endTime = "09:00"),   // 结束早于开始
                PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "23:30", endTime = "24:30"),   // 时间越界
            )
        )
        assertFalse(result.success)
        assertNull(result.draftId)
        assertEquals(4, result.issues.size)
        // 数据库没有任何日程写入
        assertTrue(scheduleEventDAO.getEventsForDate(today.toEpochDay()).isEmpty())
    }

    @Test
    fun draftAcceptsOverlappingItemsWithWarnings() = runBlocking {
        scheduleEventDAO.insertEvent(event(taskId = 2, date = today, start = 540, end = 600))
        val result = facade.createDraft(
            listOf(
                PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "09:30", endTime = "10:30"), // 与 9:00-10:00 重叠
                PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "10:00", endTime = "11:00"), // 相邻，无警告
            )
        )
        // 重叠允许保存，但必须带出提示：第 1 条与已有日程重叠，第 2 条与草案内第 1 条重叠
        assertTrue(result.success)
        assertEquals(2, result.validItems.size)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings[0].contains("已有日程"))
        assertTrue(result.warnings[0].contains("写周报")) // 提示里包含冲突日程的待办名
        assertTrue(result.warnings[1].contains("草案内其他条目"))
        assertTrue(scheduleEventDAO.getEventsForDate(today.toEpochDay()).size == 1) // 草案未写库
    }

    @Test
    fun saveWritesConfirmedDraftAndConsumesDraftId() = runBlocking {
        val draft = facade.createDraft(
            listOf(
                PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "09:00", endTime = "09:15"),
                PlannerDraftValidator.ItemInput(taskId = 3, date = todayText, startTime = "10:00", endTime = "11:00"),
            )
        )
        assertTrue(draft.success)
        val draftId = draft.draftId!!

        val saved = facade.saveDraft(draftId)
        assertTrue(saved.success)
        assertEquals(2, saved.saved.size)
        // 数据库真实写入
        val events = scheduleEventDAO.getEventsForDate(today.toEpochDay())
        assertEquals(2, events.size)
        // 同一待办被安排多次（task 1 在另一天再次排程）
        val again = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(taskId = 1, date = "2026-10-26", startTime = "09:00", endTime = "09:15"))
        )
        val savedAgain = facade.saveDraft(again.draftId!!)
        assertTrue(savedAgain.success)
        assertEquals(3, scheduleDAO.getAllSchedules().first().size) // 待办仍是 3 条，未重复创建

        // draftId 一次性消费：重复保存被拒绝
        val duplicate = facade.saveDraft(draftId)
        assertFalse(duplicate.success)
        assertNotNull(duplicate.error)
        assertEquals(2, scheduleEventDAO.getEventsForDate(today.toEpochDay()).size) // 无重复写入
        assertEquals(1, scheduleEventDAO.getEventsForDate(LocalDate.parse("2026-10-26").toEpochDay()).size)
    }

    @Test
    fun saveSucceedsWithWarningsWhenScheduleChangedAfterDraft() = runBlocking {
        val draft = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "15:00", endTime = "16:00"))
        )
        assertTrue(draft.success)
        assertFalse(draft.warnings.isNotEmpty()) // 草案时无重叠
        // 草案生成后，其他日程占用了同一时段
        scheduleEventDAO.insertEvent(event(taskId = 2, date = today, start = 900, end = 960))

        val saved = facade.saveDraft(draft.draftId!!)
        // 重叠允许保存，但 warnings 基于保存时的最新日程状态
        assertTrue(saved.success)
        assertEquals(1, saved.saved.size)
        assertEquals(1, saved.warnings.size)
        assertTrue(saved.warnings[0].contains("时间重叠"))
        assertEquals(2, scheduleEventDAO.getEventsForDate(today.toEpochDay()).size)
    }

    @Test
    fun saveRejectsDraftWhenTodoDeleted() = runBlocking {
        val draft = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "15:00", endTime = "16:00"))
        )
        scheduleDAO.deleteSchedule(1) // 草案生成后待办被删除
        val saved = facade.saveDraft(draft.draftId!!)
        assertFalse(saved.success)
        assertTrue(saved.errors[0].contains("不存在") || saved.errors[0].contains("删除"))
        assertTrue(scheduleEventDAO.getEventsForDate(today.toEpochDay()).isEmpty())
    }

    @Test
    fun modifyEventReschedulesTimeWithoutTouchingTodo() = runBlocking {
        // 先保存一条日程
        val add = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "09:00", endTime = "10:00"))
        )
        val added = facade.saveDraft(add.draftId!!)
        assertTrue(added.success)
        val originalEventId = added.saved[0].eventId

        // 修改：挪到 14:00-15:30（event_id 语义）
        val modify = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(eventId = originalEventId, date = todayText, startTime = "14:00", endTime = "15:30"))
        )
        assertTrue(modify.success)
        assertEquals(originalEventId, modify.validItems[0].eventId)

        val saved = facade.saveDraft(modify.draftId!!)
        assertTrue(saved.success)
        // 同一 event_id、时间已更新、待办未重复创建、无自指警告
        val events = scheduleEventDAO.getEventsForDate(today.toEpochDay())
        assertEquals(1, events.size)
        assertEquals(originalEventId, events[0].id)
        assertEquals(840, events[0].startTime) // 14:00
        assertEquals(930, events[0].endTime)   // 15:30
        assertEquals(3, scheduleDAO.getAllSchedules().first().size) // 待办仍是 3 条，未重复创建
        assertTrue(saved.warnings.isEmpty())

        // 修改时不允许更换关联待办
        val changeTask = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(eventId = originalEventId, taskId = 2, date = todayText, startTime = "16:00", endTime = "17:00"))
        )
        assertFalse(changeTask.success)
        assertTrue(changeTask.issues[0].contains("不支持更换"))
    }

    @Test
    fun saveSkipsModifyWhenEventDeleted() = runBlocking {
        val eventId = scheduleEventDAO.insertEvent(event(taskId = 1, date = today, start = 540, end = 600))
        val draft = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(eventId = eventId, date = todayText, startTime = "14:00", endTime = "15:00"))
        )
        assertTrue(draft.success)
        scheduleEventDAO.deleteEvent(eventId) // 草案生成后日程被删除

        val saved = facade.saveDraft(draft.draftId!!)
        assertFalse(saved.success)
        assertTrue(saved.errors[0].contains("删除"))
        assertTrue(scheduleEventDAO.getEventsForDate(today.toEpochDay()).isEmpty())
    }

    @Test
    fun deletingTodoCascadesItsEvents() = runBlocking {
        scheduleEventDAO.insertEvent(event(taskId = 1, date = today, start = 540, end = 600))
        assertEquals(1, scheduleEventDAO.getEventsForDate(today.toEpochDay()).size)
        scheduleDAO.deleteSchedule(1) // 外键 CASCADE：待办删除时日程一并删除（沿用现有规则）
        assertEquals(0, scheduleEventDAO.getEventsForDate(today.toEpochDay()).size)
    }

    @Test
    fun ongoingEventMatchesCurrentTimeWindow() = runBlocking {
        val now = java.time.LocalDateTime.of(2026, 10, 25, 9, 30)
        scheduleEventDAO.insertEvent(event(taskId = 1, date = today, start = 540, end = 600))  // 9:00-10:00 进行中
        scheduleEventDAO.insertEvent(event(taskId = 2, date = today, start = 660, end = 720))  // 11:00-12:00 未开始
        scheduleEventDAO.insertEvent(event(taskId = 3, date = today, start = 480, end = 540))  // 8:00-9:00 已结束
        val ongoing = facade.currentOngoingEvents(now)
        assertEquals(1, ongoing.size)
        assertEquals(540, ongoing[0].startMinute)
    }

    @Test
    fun scheduledTaskIdsReflectEventLifecycle() = runBlocking {
        // 初始无日程：所有待办均为未排程
        assertTrue(facade.scheduledTaskIds().isEmpty())

        // 保存草案后：task1 进入已排程集合
        val draft = facade.createDraft(
            listOf(PlannerDraftValidator.ItemInput(taskId = 1, date = todayText, startTime = "09:00", endTime = "10:00"))
        )
        facade.saveDraft(draft.draftId!!)
        assertTrue(facade.scheduledTaskIds().contains(1L))

        // 删除最后一条日程：待办自动回到未排程（实时推导，无需维护状态字段）
        val events = scheduleEventDAO.getEventsForDate(today.toEpochDay())
        scheduleEventDAO.deleteEvent(events[0].id)
        assertFalse(facade.scheduledTaskIds().contains(1L))
    }

    // ---------------- Markdown → PDF 三种纸张 ----------------

    private val sampleMarkdown = """
        # 今日计划 · 10月25日

        ## 已保存日程
        - 09:00 - 09:15 学习 Kotlin
        - 10:00 - 11:00 重构导出模块

        ## 待办清单
        1. 写周报（截止 10/26）
        2. **读书 30 分钟**
        - [x] 完成排程草案
        - [ ] 确认导出 PDF

        > 内容基于真实日程数据生成，草案与已保存日程已区分。

        ---

        备注：这是一个用于验证 Markdown 转 PDF 渲染、中文换行与分页逻辑的较长段落，
        包含英文单词 Sandbox 与数字 1234567890，用于检查窄纸张下的断行表现。
    """.trimIndent()

    @Test
    fun exportMarkdownPdfOnAllPaperSizes() = runBlocking {
        PlannerPaper.entries.forEach { paper ->
            val result = facade.exportMarkdownPdf(sampleMarkdown, paper, landscape = false)
            assertTrue("${paper.name} 导出应成功", result.success)
            assertTrue("${paper.name} 页数应大于 0", result.pages > 0)
            val pdf = result.pdfFile!!
            assertTrue("${paper.name} PDF 应存在且非空", pdf.exists() && pdf.length() > 1000)
            assertNotNull("${paper.name} 预览图应生成", result.previewFile)
            assertTrue(result.previewFile!!.length() > 1000)
        }
    }

    @Test
    fun thermalPaperGeneratesSinglePageWithContentBasedHeight() {
        val renderer = PlannerMarkdownPdfRenderer()
        val captured = mutableListOf<Pair<Int, Int>>() // width to height
        renderer.render(sampleMarkdown, PlannerPaper.PAPER_58MM, landscape = false) { _, w, h, _ ->
            captured.add(w to h)
        }
        // 连续纸：短内容只生成一页，且页高按内容计算（小于 A4 固定页高 842）
        assertEquals(1, captured.size)
        val (width, height) = captured[0]
        assertTrue("热敏纸页高应按内容计算（实际 $height，应小于 842 且不小于 144）", height in 144 until 842)
        assertEquals("热敏纸页宽应为 58mm（164pt）", 164, width)

        // A4 仍为固定页高
        val a4Heights = mutableListOf<Int>()
        renderer.render(sampleMarkdown, PlannerPaper.A4, landscape = false) { _, _, h, _ -> a4Heights.add(h) }
        assertTrue(a4Heights.all { it == 842 })

        // 超长内容：连续纸高度到达上限后退化为分页，不裁切
        val longMarkdown = (sampleMarkdown + "\n\n补充段落：继续打印的内容。").repeat(60)
        var longPages = 0
        renderer.render(longMarkdown, PlannerPaper.PAPER_58MM, landscape = false) { _, _, _, _ -> longPages++ }
        assertTrue("超长内容应至少一页", longPages >= 1)

        // A4 横向：宽 842、高 595（回归检查：页高需跟随换向）
        val landscapeDims = mutableListOf<Pair<Int, Int>>()
        renderer.render(sampleMarkdown, PlannerPaper.A4, landscape = true) { _, w, h, _ -> landscapeDims.add(w to h) }
        assertTrue(landscapeDims.all { it.first == 842 && it.second == 595 })
    }

    @Test
    fun exportLandscapeA4AndRejectEmptyMarkdown() = runBlocking {
        val landscape = facade.exportMarkdownPdf(sampleMarkdown, PlannerPaper.A4, landscape = true)
        assertTrue(landscape.success)
        val empty = facade.exportMarkdownPdf("   ", PlannerPaper.A4, landscape = false)
        assertFalse(empty.success)
        assertNotNull(empty.error)
    }

    private fun event(taskId: Long, date: LocalDate, start: Int, end: Int) =
        me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity(
            taskId = taskId,
            date = date.toEpochDay(),
            startTime = start,
            endTime = end,
            category = "user",
        )
}
