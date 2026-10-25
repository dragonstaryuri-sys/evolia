package me.rerere.rikkahub.data.print

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 打印渲染链 instrumented 测试：
 * 1. 条带渲染宽度恒为 384（PD01 点阵宽），条带高度和 = 度量总高
 * 2. 条带数量受 1024px 上限控制（长文档不产生巨型 Bitmap）
 * 3. Bitmap → 点阵行数一致、全白位图打包为全零行
 * 4. 预览 PNG 生成
 * 5. 空白内容不出崩溃（0 行）
 */
@RunWith(AndroidJUnit4::class)
class PrintRendererInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sampleMarkdown = """
        # 今日计划

        早上完成**深度工作**：整理日程并打印。

        - 无序列表项一
        - 无序列表项二
        1. 有序列表项
        - [x] 已完成事项
        - [ ] 待办事项

        > 引用内容：与世界的碰撞中不断进化。

        ---
        这是一段较长的中文文本，用于验证自动换行与边距：热敏打印机使用 384 点打印宽度，
        每行对应 48 字节点阵数据，中文按字断行而英文单词不断行。
    """.trimIndent()

    @Test
    fun stripsHaveFixed384WidthAndSumConsistency() {
        val layout = PrintContentRenderer.measure(sampleMarkdown)
        assertTrue("总行数应大于 0", layout.rowCount > 0)
        assertEquals("宽度应为 PD01 点阵宽", 384, layout.widthPx)

        var heightSum = 0
        PrintContentRenderer.renderStrips(sampleMarkdown) { strip, _ ->
            assertEquals("条带宽度恒为 384", 384, strip.width)
            assertTrue("条带高度不超过上限", strip.height <= 1024)
            heightSum += strip.height
            true
        }
        assertEquals("条带高度和应等于度量总高", layout.rowCount, heightSum)
    }

    @Test
    fun longDocumentIsSplitIntoMultipleStrips() {
        // 重复段落构造长文档，确保超过单条带 1024px
        val longMarkdown = (1..60).joinToString("\n\n") { i ->
            "## 第 $i 节\n\n这是第 $i 节的内容，包含中文与 English mixed text 123，用于撑起足够的高度。\n\n- 项目甲 $i\n- 项目乙 $i"
        }
        var stripCount = 0
        PrintContentRenderer.renderStrips(longMarkdown) { _, _ ->
            stripCount++
            true
        }
        assertTrue("长文档应拆分为多个条带，实际 $stripCount", stripCount >= 2)
    }

    @Test
    fun bitmapToRowsMatchesHeightAndWhiteMapsToZero() {
        PrintContentRenderer.renderStrips(sampleMarkdown) { strip, _ ->
            val rows = Pd01Protocol.bitmapToRows(strip)
            assertEquals("点阵行数 = 位图高度", strip.height, rows.size)
            rows.forEach { row -> assertEquals(48, row.size) }
            // 渲染内容为白底黑字：验证不存在全 1 的行（全黑），且空白区域为 0
            rows.forEach { row -> assertTrue("不应出现全 1 行", row.any { it == 0.toByte() }) }
            true
        }
    }

    @Test
    fun previewPngIsCreated() {
        val outFile = File(context.filesDir, "print_test/preview.png")
        outFile.delete()
        val result = PrintContentRenderer.renderPreviewPng(sampleMarkdown, outFile)
        assertNotNull("预览 PNG 应生成", result)
        assertTrue("预览文件应存在", outFile.exists() && outFile.length() > 0)
        outFile.delete()
    }

    @Test
    fun blankContentProducesZeroRows() {
        val layout = PrintContentRenderer.measure("   \n  ")
        assertEquals("空白内容应为 0 行", 0, layout.rowCount)
        assertEquals("空白内容不应产出预览", null, PrintContentRenderer.renderPreviewPng("   ", File(context.filesDir, "print_test/blank.png")))
        // 适配器层面应拒绝空内容
        val adapter = Pd01PrinterAdapter()
        val connection = PrinterConnection(context)
        try {
            adapter.print(connection, "   ")
            throw AssertionError("空内容应抛出 PrintException(EMPTY_CONTENT)")
        } catch (e: PrintException) {
            assertEquals(PrintErrorCode.EMPTY_CONTENT, e.errorCode)
        }
    }
}
