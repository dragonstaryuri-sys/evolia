package me.rerere.rikkahub.data.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * SchedulePlanner 纯逻辑单测：
 * - PlannerDraftValidator：时间解析、冲突判断（JVM 可运行，不依赖 Android）；
 * - PlannerMarkdownParser：日计划 Markdown 的块级解析。
 */
class PlannerDraftValidatorTest {

    @Test
    fun `parse time accepts HHmm and rejects bad values`() {
        assertEquals(PlannerDraftValidator.ParsedTime(LocalDate.of(2026, 10, 25), 540, 600), PlannerDraftValidator.parseTime("2026-10-25", "9:00", "10:00"))
        assertEquals(PlannerDraftValidator.ParsedTime(LocalDate.of(2026, 10, 25), 1410, 1439), PlannerDraftValidator.parseTime("2026-10-25", "23:30", "23:59"))
        assertNull(PlannerDraftValidator.parseTime("2026-10-25", "24:00", "25:00"))   // 小时越界
        assertNull(PlannerDraftValidator.parseTime("2026-10-25", "9:60", "10:00"))   // 分钟越界
        assertNull(PlannerDraftValidator.parseTime("2026-10-25", "9:00", "9:00"))    // 结束不晚于开始
        assertNull(PlannerDraftValidator.parseTime("2026-10-25", "10:00", "9:00"))   // 结束早于开始
        assertNull(PlannerDraftValidator.parseTime("2026/10/25", "9:00", "10:00"))   // 日期格式
        assertNull(PlannerDraftValidator.parseTime(null, "9:00", "10:00"))
        assertNull(PlannerDraftValidator.parseTime("2026-10-25", "9点", "10:00"))
    }
}

class PlannerMarkdownParserTest {

    @Test
    fun `parses headings paragraphs lists checkbox quote and rule`() {
        val md = """
            # 今日计划
            上午重点处理排期。

            ## 已保存日程
            - 09:00 - 10:00 学习 Kotlin
            - 11:00 - 12:00 散步

            ## 待办
            1. 写周报
            2. 读书

            - [x] 完成排版功能
            - [ ] 继续打磨导出

            > 注意区分草案与已保存日程
            ---
        """.trimIndent()
        val blocks = PlannerMarkdownParser.parse(md)
        assertEquals(12, blocks.size)
        assertTrue(blocks[0] is PlannerMarkdownParser.MdBlock.Heading)
        assertEquals(1, (blocks[0] as PlannerMarkdownParser.MdBlock.Heading).level)
        assertTrue(blocks[1] is PlannerMarkdownParser.MdBlock.Paragraph)
        assertTrue(blocks[2] is PlannerMarkdownParser.MdBlock.Heading)
        val savedList = blocks[3] as PlannerMarkdownParser.MdBlock.ListItem
        assertNull(savedList.checked)
        assertFalse(savedList.ordered)
        val ordered = blocks[6] as PlannerMarkdownParser.MdBlock.ListItem
        assertTrue(ordered.ordered)
        assertEquals("1.", ordered.marker)
        val checked = blocks[8] as PlannerMarkdownParser.MdBlock.ListItem
        assertEquals(true, checked.checked)
        val unchecked = blocks[9] as PlannerMarkdownParser.MdBlock.ListItem
        assertEquals(false, unchecked.checked)
        assertTrue(blocks[10] is PlannerMarkdownParser.MdBlock.Quote)
        assertTrue(blocks[11] is PlannerMarkdownParser.MdBlock.Rule)
    }

    @Test
    fun `inline bold is parsed into runs`() {
        val runs = PlannerMarkdownParser.parseRuns("重点是 **时间冲突检查** 与数据一致")
        assertEquals(3, runs.size)
        assertEquals("重点是 ", runs[0].text)
        assertFalse(runs[0].bold)
        assertEquals("时间冲突检查", runs[1].text)
        assertTrue(runs[1].bold)
        assertEquals(" 与数据一致", runs[2].text)
    }

    @Test
    fun `unknown syntax degrades to paragraph and never throws`() {
        // 连续非空行按 Markdown 语义合并为一个段落
        val blocks = PlannerMarkdownParser.parse("普通一行\n![图片](http://x.png)\n| 表格 | 不支持 |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is PlannerMarkdownParser.MdBlock.Paragraph)
        val text = (blocks[0] as PlannerMarkdownParser.MdBlock.Paragraph).runs.joinToString("") { it.text }
        assertTrue(text.contains("普通一行"))
        assertTrue(text.contains("表格"))
    }

    @Test
    fun `unclosed fence still renders content`() {
        val blocks = PlannerMarkdownParser.parse("```\n代码未闭合")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is PlannerMarkdownParser.MdBlock.Paragraph)
        assertEquals("代码未闭合", (blocks[0] as PlannerMarkdownParser.MdBlock.Paragraph).runs.first().text)
    }
}
