package me.rerere.rikkahub.data.print

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import me.rerere.rikkahub.data.planner.PlannerMarkdownPdfRenderer
import me.rerere.rikkahub.data.planner.PlannerPaper

/**
 * Markdown 打印内容渲染：
 * - 打印位图：复用 [PlannerMarkdownPdfRenderer] 的条带渲染，按打印机点阵宽度
 *   （PD01 = 384px）逐条带生成 Bitmap，不经 PDF 中间文件；
 * - 预览 PNG：同一版面放大绘制，供任务卡片检查实际排版。
 *
 * 打印版面复用 58mm 热敏纸规格（与 58mm PDF 导出一致），缩放后恰好映射到
 * 打印头 384 点宽，保证打印内容与 PDF 主要排版一致。
 */
object PrintContentRenderer {

    /** 渲染度量：高度像素即点阵行数（200DPI 下 8 行 ≈ 1mm） */
    data class PrintLayout(val widthPx: Int, val heightPx: Int) {
        val rowCount: Int get() = heightPx
        val estimatedLengthMm: Float get() = heightPx / 8f
    }

    /** PD01 打印头宽度（点） */
    const val PRINT_WIDTH_PX = 384

    /** 单条带像素高度上限：控制打印期 Bitmap 内存峰值（384×1024×4 ≈ 1.5MB） */
    private const val MAX_STRIP_HEIGHT_PX = 1024

    private const val PREVIEW_TARGET_WIDTH_PX = 720f
    private const val MAX_PREVIEW_HEIGHT_PX = 4200

    private val renderer = PlannerMarkdownPdfRenderer()

    /** 打印版面：58mm 热敏纸规格（与 58mm PDF 导出同一版面规则） */
    val printPaper: PlannerPaper = PlannerPaper.PAPER_58MM

    /** 打印位图缩放：164.4pt 版面宽 → 384px 点阵宽 */
    val printScale: Float = PRINT_WIDTH_PX / printPaper.widthPt

    /** 预量版面：总像素高度（=点阵行数）与宽度，不创建 Bitmap；空白内容为 0 行 */
    fun measure(markdown: String): PrintLayout {
        if (markdown.isBlank()) return PrintLayout(PRINT_WIDTH_PX, 0)
        var widthPx = PRINT_WIDTH_PX
        var heightPx = 0
        renderer.renderPrintStrips(markdown, printPaper, printScale, MAX_STRIP_HEIGHT_PX) { _, w, h, _ ->
            widthPx = w
            heightPx += h
            true
        }
        return PrintLayout(widthPx, heightPx)
    }

    /**
     * 逐条带渲染 Bitmap（ARGB，白底，黑白转换前的灰度源）。
     * 每个条带绘制完成后立即回调，调用方负责转换点阵；
     * 条带 Bitmap 在回调返回后由本方法回收，调用方不得持有引用。
     * 回调返回 false 中止后续条带（用户取消 / 发送失败）。
     */
    fun renderStrips(markdown: String, onStrip: (Bitmap, stripIndex: Int) -> Boolean): PrintLayout {
        val layout = measure(markdown)
        if (layout.rowCount <= 0) return layout
        renderer.renderPrintStrips(markdown, printPaper, printScale, MAX_STRIP_HEIGHT_PX) { index, widthPx, heightPx, draw ->
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            val continued = onStrip(bitmap, index)
            bitmap.recycle()
            continued
        }
        return layout
    }

    /** 渲染预览 PNG（尽量整篇，超高时按高度钳制倍率）到目标文件；空白内容返回 null */
    fun renderPreviewPng(markdown: String, outFile: File): File? {
        if (markdown.isBlank()) return null
        val widthPt = printPaper.widthPt
        // scale=1 预量内容总高（pt），用于按高度钳制预览倍率
        var totalHeightPt = 0f
        renderer.renderPrintStrips(markdown, printPaper, 1f, MAX_PREVIEW_HEIGHT_PX.toInt()) { _, _, h, _ ->
            totalHeightPt += h
            true
        }
        val safeHeightPt = totalHeightPt.coerceAtLeast(1f)
        val previewScale = minOf(
            PREVIEW_TARGET_WIDTH_PX / widthPt,
            MAX_PREVIEW_HEIGHT_PX.toFloat() / safeHeightPt
        ).coerceIn(1.5f, 6f)

        var wrote = false
        renderer.renderPrintStrips(markdown, printPaper, previewScale, MAX_PREVIEW_HEIGHT_PX) { _, w, h, draw ->
            if (wrote) return@renderPrintStrips false
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            wrote = true
            false
        }
        return if (wrote) outFile else null
    }
}
