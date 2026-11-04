package me.rerere.rikkahub

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryGenerationStatus
import me.rerere.rikkahub.service.DiaryEntryAction
import me.rerere.rikkahub.service.buildDiaryMessageGroups
import me.rerere.rikkahub.service.chunkCursorMillis
import me.rerere.rikkahub.service.computeStartTimeThreshold
import me.rerere.rikkahub.service.createdAtMillis
import me.rerere.rikkahub.service.resolveDiaryEntryAction
import me.rerere.rikkahub.service.shouldAutoTriggerDiary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日记生成失败恢复的纯决策逻辑验证。
 *
 * 覆盖需求场景（详见需求文档测试表）：
 * - 场景 1/3/4/5：chunk 级游标推进与失败保留
 * - 场景 6/7：PARTIAL 同天恢复
 * - 场景 8：跨天承接（锚点链）
 * - 场景 10：GENERATING 卡死恢复
 * - 场景 11：无交互日记游标
 * - 场景 12：用户图片日记不被误判
 * - 场景 14：同毫秒时间戳不漏消息
 * - 场景 15：历史日记缺 lastMessageAt 兼容
 * - 场景 16：空失败日记可重试
 * - 场景 17：手动生成仍以今天为目标（DiaryWorker targetDate 兜底逻辑，此处验证入口判定不依赖日期）
 */
class DiaryGenerationLogicTest {

    // ---------- 测试工具 ----------

    private fun msg(
        text: String,
        dateTime: String,
        role: MessageRole = MessageRole.USER
    ): UIMessage = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse(dateTime)
    )

    private fun diary(
        id: String = "d1",
        content: String = "",
        status: String = DiaryGenerationStatus.COMPLETED,
        images: List<me.rerere.rikkahub.core.data.db.entity.DiaryImage> = emptyList(),
        date: String = "2026-01-01",
        lastMessageAt: Long? = null,
        createdAt: Long = 1000L
    ): AgentDiaryEntity = AgentDiaryEntity(
        id = id,
        assistantId = "agent",
        content = content,
        images = images,
        date = date,
        lastMessageAt = lastMessageAt,
        createdAt = createdAt,
        generationStatus = status
    )

    private fun imageDiary() = listOf(
        me.rerere.rikkahub.core.data.db.entity.DiaryImage(imagePath = "/fake/path.webp")
    )

    // ---------- resolveDiaryEntryAction（入口判定） ----------

    @Test
    fun `场景-无记录-创建新日记`() {
        val action = resolveDiaryEntryAction(existingDiary = null, isOtherGenerationTaskActive = false)
        assertEquals(DiaryEntryAction.CreateNew, action)
    }

    @Test
    fun `场景6-13-当天日记已完成-不重复生成`() {
        val completed = diary(content = "正文", status = DiaryGenerationStatus.COMPLETED, lastMessageAt = 5000L)
        val action = resolveDiaryEntryAction(completed, isOtherGenerationTaskActive = false)
        assertEquals(DiaryEntryAction.SkipAlreadyExists, action)
    }

    @Test
    fun `场景7-PARTIAL日记-允许同天续写恢复`() {
        val partial = diary(content = "已生成的部分", status = DiaryGenerationStatus.PARTIAL, lastMessageAt = 5000L)
        val action = resolveDiaryEntryAction(partial, isOtherGenerationTaskActive = false)
        assertTrue(action is DiaryEntryAction.Resume)
        assertEquals(partial.id, (action as DiaryEntryAction.Resume).diary.id)
    }

    @Test
    fun `场景10-GENERATING且无活跃任务-接管恢复`() {
        // 进程被杀留下的 GENERATING 残留：无活跃 WorkManager 任务 → 必须可接管，不得永久卡死
        val stale = diary(content = "崩溃前已保存的内容", status = DiaryGenerationStatus.GENERATING, lastMessageAt = 5000L)
        val action = resolveDiaryEntryAction(stale, isOtherGenerationTaskActive = false)
        assertTrue(action is DiaryEntryAction.Resume)
    }

    @Test
    fun `GENERATING且其他任务活跃-跳过避免双任务并发`() {
        val generating = diary(content = "", status = DiaryGenerationStatus.GENERATING)
        val action = resolveDiaryEntryAction(generating, isOtherGenerationTaskActive = true)
        assertEquals(DiaryEntryAction.SkipTaskRunning, action)
    }

    @Test
    fun `场景12-用户图片日记-即使正文为空也不删除不重建`() {
        // 用户以智能体身份创建的扫描日记：images 非空、正文为空
        // 必须跳过（绝不删除），否则旧逻辑 content.isBlank() 会误删用户数据
        val userImageDiary = diary(content = "", status = DiaryGenerationStatus.COMPLETED, images = imageDiary())
        val action = resolveDiaryEntryAction(userImageDiary, isOtherGenerationTaskActive = false)
        assertEquals(DiaryEntryAction.SkipAlreadyExists, action)
    }

    @Test
    fun `场景16-空失败日记-可复用重试不被空记录阻塞`() {
        // 历史遗留空壳（崩溃残留 / 老版本骨架）：复用原记录重建，不创建重复日记
        val emptyShell = diary(content = "", status = DiaryGenerationStatus.COMPLETED)
        val action = resolveDiaryEntryAction(emptyShell, isOtherGenerationTaskActive = false)
        assertTrue(action is DiaryEntryAction.Resume)
        assertEquals(emptyShell.id, (action as DiaryEntryAction.Resume).diary.id)
    }

    // ---------- shouldAutoTriggerDiary（自动调度判定） ----------

    @Test
    fun `场景7-调度遇PARTIAL-触发恢复`() {
        val partial = diary(content = "部分", status = DiaryGenerationStatus.PARTIAL, lastMessageAt = 5000L)
        assertTrue(shouldAutoTriggerDiary(partial, isGenerationTaskActive = false))
    }

    @Test
    fun `调度遇COMPLETED-跳过`() {
        val completed = diary(content = "正文", status = DiaryGenerationStatus.COMPLETED, lastMessageAt = 5000L)
        assertFalse(shouldAutoTriggerDiary(completed, isGenerationTaskActive = false))
    }

    @Test
    fun `调度遇GENERATING且任务活跃-跳过`() {
        val generating = diary(content = "", status = DiaryGenerationStatus.GENERATING)
        assertFalse(shouldAutoTriggerDiary(generating, isGenerationTaskActive = true))
    }

    @Test
    fun `调度遇GENERATING但任务已死-重新调度`() {
        val generating = diary(content = "", status = DiaryGenerationStatus.GENERATING)
        assertTrue(shouldAutoTriggerDiary(generating, isGenerationTaskActive = false))
    }

    @Test
    fun `调度遇用户图片日记-跳过`() {
        val userImageDiary = diary(content = "", images = imageDiary())
        assertFalse(shouldAutoTriggerDiary(userImageDiary, isGenerationTaskActive = false))
    }

    // ---------- computeStartTimeThreshold（锚点计算 / 跨天承接） ----------

    @Test
    fun `场景8-跨天承接-新日记锚点取最近一篇有正文日记的游标`() {
        // 1月1日 PARTIAL（游标停在 1月1日20:00），1月2日新建日记应从该游标继续
        val jan1Cursor = LocalDateTime.parse("2026-01-01T20:00:00")
            .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val jan1Partial = diary(
            id = "jan1",
            content = "1月1日部分内容",
            status = DiaryGenerationStatus.PARTIAL,
            date = "2026-01-01",
            lastMessageAt = jan1Cursor
        )
        val threshold = computeStartTimeThreshold(
            resumeDiary = null,
            otherDiariesSortedByDateDesc = listOf(jan1Partial),
            fallbackStartOfDayMillis = LocalDateTime.parse("2026-01-02T00:00:00")
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        )
        assertEquals(jan1Cursor, threshold)
    }

    @Test
    fun `场景6-同天PARTIAL续写-锚点优先取自身游标`() {
        // PARTIAL 日记自身游标 = 上次实际处理到的位置，续写必须从这里开始
        val ownCursor = 7777_7777_7777L
        val partial = diary(
            content = "已有正文",
            status = DiaryGenerationStatus.PARTIAL,
            lastMessageAt = ownCursor
        )
        val olderCursor = 1111_1111_1111L
        val older = diary(id = "older", content = "旧日记", lastMessageAt = olderCursor, date = "2025-12-31")
        val threshold = computeStartTimeThreshold(
            resumeDiary = partial,
            otherDiariesSortedByDateDesc = listOf(older),
            fallbackStartOfDayMillis = 0L
        )
        assertEquals(ownCursor, threshold)
    }

    @Test
    fun `场景15-历史日记缺lastMessageAt-回退兼容规则不回溯更早日记`() {
        // 最近一篇日记游标为 null（老数据），回退行为与旧逻辑一致：
        // 直接使用目标日期 0 点，不回溯更早日记的游标（保持历史兼容规则不变）
        val legacy = diary(id = "legacy", content = "老日记", lastMessageAt = null, date = "2025-12-31")
        val dayStart = LocalDateTime.parse("2026-01-01T00:00:00")
            .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val threshold = computeStartTimeThreshold(
            resumeDiary = null,
            otherDiariesSortedByDateDesc = listOf(legacy),
            fallbackStartOfDayMillis = dayStart
        )
        assertEquals(dayStart, threshold)
    }

    // ---------- buildDiaryMessageGroups（分 chunk + 同毫秒 snap + 游标） ----------

    @Test
    fun `场景1-全部chunk成功-游标可逐chunk推进到最后一条`() {
        val m1 = msg("a".repeat(30), "2026-01-01T10:00:00.100")
        val m2 = msg("b".repeat(30), "2026-01-01T10:00:01.200")
        val m3 = msg("c".repeat(30), "2026-01-01T10:00:02.300")
        val groups = buildDiaryMessageGroups(listOf(m1, m2, m3), chunkSize = 50)

        // chunkSize=50、单条 30 字符 → 每条各占一组
        assertEquals(3, groups.size)
        assertEquals(listOf(m1.id), groups[0].map { it.id })
        assertEquals(listOf(m2.id), groups[1].map { it.id })
        assertEquals(listOf(m3.id), groups[2].map { it.id })

        // 模拟逐 chunk 成功：游标依次推进到各 chunk 末条，最终 = 全部消息最后一条
        val cursor1 = chunkCursorMillis(groups[0])!!
        val cursor2 = chunkCursorMillis(groups[1])!!
        val cursor3 = chunkCursorMillis(groups[2])!!
        assertTrue(cursor1 < cursor2 && cursor2 < cursor3)
        assertEquals(m3.createdAtMillis(), cursor3)
    }

    @Test
    fun `场景14-同毫秒消息不跨chunk边界-严格大于查询不漏`() {
        // 微信模式分句存储：同一毫秒落库多条（t1 组 3 条）
        val t1a = msg("x".repeat(40), "2026-01-01T10:00:00.123")
        val t1b = msg("y".repeat(40), "2026-01-01T10:00:00.123")
        val t1c = msg("z".repeat(40), "2026-01-01T10:00:00.123")
        val t2 = msg("w".repeat(40), "2026-01-01T10:00:01.456")
        val groups = buildDiaryMessageGroups(listOf(t1a, t1b, t1c, t2), chunkSize = 60)

        // t1 组必须完整待在同一个 chunk（即使超体积），游标推进到 t2 后不会漏掉同 ts 的 t1 成员
        assertEquals(2, groups.size)
        assertEquals(listOf(t1a.id, t1b.id, t1c.id), groups[0].map { it.id })
        assertEquals(listOf(t2.id), groups[1].map { it.id })

        // 模拟 chunk1 成功、chunk2 失败：游标 = chunk1 最后一条消息的 ts
        val cursor = chunkCursorMillis(groups[0])
        // 后继 chunk 首条消息必须严格大于该游标
        assertTrue(chunkCursorMillis(groups[1])!! > cursor!!)
        assertEquals(t1c.createdAtMillis(), cursor)
    }

    @Test
    fun `场景3-游标推进值等于chunk最后一条消息时间`() {
        val m1 = msg("a", "2026-01-01T10:00:00.100")
        val m2 = msg("b", "2026-01-01T10:00:00.100")
        val groups = buildDiaryMessageGroups(listOf(m1, m2), chunkSize = 40_000)

        // 同毫秒组不可拆分
        assertEquals(1, groups.size)
        assertEquals(m2.createdAtMillis(), chunkCursorMillis(groups[0]))
    }

    @Test
    fun `空chunk返回null游标-不推进`() {
        assertNull(chunkCursorMillis(emptyList()))
    }
}
