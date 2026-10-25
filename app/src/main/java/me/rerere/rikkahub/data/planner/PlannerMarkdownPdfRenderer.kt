package me.rerere.rikkahub.data.planner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.round

/**
 * Markdown 日计划 PDF 渲染器。
 *
 * 输入为 AI 生成的 Markdown（已由 [PlannerMarkdownParser] 解析），
 * 按 [PlannerPaper] 的纸张规格（A4 / 58mm / 80mm 热敏纸）动态调整
 * 页宽、边距、字号与换行，内容超出页面时自动分页，不裁切文本行。
 * 输出与介质解耦：PDF 按 pt 1:1 绘制，预览 PNG 放大绘制同一份结果。
 *
 * 配色固定为黑白灰高对比（打印友好），不再使用固定主题与贴纸装饰。
 */
class PlannerMarkdownPdfRenderer {

    fun interface PageConsumer {
        fun onPage(index: Int, widthPt: Int, heightPt: Int, draw: (Canvas) -> Unit)
    }

    private class Palette {
        val text = Color.rgb(0x1A, 0x1A, 0x1A)
        val subtle = Color.rgb(0x75, 0x75, 0x75)
        val divider = Color.rgb(0xD9, 0xD9, 0xD9)
    }

    fun render(markdown: String, paper: PlannerPaper, landscape: Boolean, consumer: PageConsumer) {
        val palette = Palette()
        // 热敏小票纸只支持纵向；A4 支持纵横
        val useLandscape = landscape && paper == PlannerPaper.A4
        val pageWidth = if (useLandscape) paper.heightPt else paper.widthPt
        val margin = paper.marginPt
        val contentWidth = pageWidth - margin * 2

        val pageHeight = if (useLandscape) paper.widthPt else paper.heightPt
        val lines = layout(markdown, paper, contentWidth)
        if (paper.continuous) {
            renderContinuous(lines, paper, pageWidth, contentWidth, consumer)
        } else {
            renderPaginated(lines, paper, pageWidth, pageHeight, contentWidth, palette, consumer)
        }
    }

    /**
     * 热敏连续纸：按内容实际高度生成单页 PDF（不固定页高、无页码），
     * 高度超过 PdfDocument 单页上限时才退化为按上限分页。
     */
    private fun renderContinuous(
        lines: List<LayoutLine>,
        paper: PlannerPaper,
        pageWidth: Float,
        contentWidth: Float,
        consumer: PageConsumer,
    ) {
        val palette = Palette()
        val contentHeight = lines.sumOf { (it.height + it.spaceBefore).toDouble() }.toFloat()
        val pageHeight = (paper.marginPt * 2 + contentHeight)
            .coerceAtLeast(MIN_CONTINUOUS_PAGE_PT)
            .coerceAtMost(MAX_PDF_PAGE_PT)

        // 单页放不下（极端超长内容）时按上限分页
        val usableHeight = pageHeight - paper.marginPt * 2
        val pages = mutableListOf<List<LayoutLine>>()
        var current = mutableListOf<LayoutLine>()
        var used = 0f
        lines.forEach { line ->
            val required = line.height + line.spaceBefore
            if (current.isNotEmpty() && used + required > usableHeight) {
                pages.add(current)
                current = mutableListOf()
                used = 0f
            }
            current.add(line)
            used += required
        }
        pages.add(current)

        pages.forEachIndexed { index, pageLines ->
            val pageHeightInt = if (pages.size == 1) ceil(pageHeight).toInt() else paper.heightPt.toInt()
            consumer.onPage(index, pageWidth.toInt(), pageHeightInt) { canvas ->
                canvas.drawColor(Color.WHITE)
                var y = paper.marginPt
                pageLines.forEach { line ->
                    y += line.spaceBefore
                    drawLine(canvas, line, paper.marginPt, contentWidth, y, palette)
                    y += line.height
                }
            }
        }
    }

    /** A4 等固定尺寸纸张：按页高分页，带页码 */
    private fun renderPaginated(
        lines: List<LayoutLine>,
        paper: PlannerPaper,
        pageWidth: Float,
        pageHeight: Float,
        contentWidth: Float,
        palette: Palette,
        consumer: PageConsumer,
    ) {
        val margin = paper.marginPt
        val footerReserve = 16f
        val contentHeight = pageHeight - margin * 2 - footerReserve
        val footerPaint = textPaint(palette.subtle, paper.bodyFontPt * 0.75f, bold = false)

        // 构建期同步分页：每页为一个行区间
        data class PageLines(val lines: List<LayoutLine>)
        val pages = mutableListOf<PageLines>()
        var currentPage = mutableListOf<LayoutLine>()
        var usedHeight = 0f
        lines.forEach { line ->
            val required = line.height + line.spaceBefore
            val extraFit = if (line.mustFitExtra > 0f) line.mustFitExtra else 0f
            if (currentPage.isNotEmpty() && usedHeight + required + extraFit > contentHeight) {
                pages.add(PageLines(currentPage))
                currentPage = mutableListOf()
                usedHeight = 0f
            }
            currentPage.add(line)
            usedHeight += required
        }
        if (currentPage.isNotEmpty() || pages.isEmpty()) {
            pages.add(PageLines(currentPage))
        }

        val total = pages.size
        pages.forEachIndexed { index, page ->
            consumer.onPage(index, pageWidth.toInt(), pageHeight.toInt()) { canvas ->
                // 显式铺白底：PDF 默认白页，但预览 PNG 的透明背景会被查看器渲染为深色
                canvas.drawColor(Color.WHITE)
                var y = margin
                page.lines.forEach { line ->
                    y += line.spaceBefore
                    drawLine(canvas, line, margin, contentWidth, y, palette)
                    y += line.height
                }
                // 页码
                val pageText = "第 ${index + 1} / $total 页"
                val textWidth = footerPaint.measureText(pageText)
                canvas.drawText(
                    pageText,
                    (pageWidth - textWidth) / 2,
                    pageHeight - margin - 2f,
                    footerPaint
                )
            }
        }
    }

    /**
     * 打印专用：热敏连续纸按条带（strip）分段渲染，逐条回调给调用方转成点阵发送。
     *
     * 与 [renderContinuous] 共用同一套 [layout] 与 [drawLine]，版面规则一致；
     * 区别在于每个条带的像素高度不超过 [maxStripHeightPx]，条带之间按行边界
     * 无缝衔接（像素边界由累计 pt 坐标取整得到，不产生累积缝隙），
     * 从而避免为长文档一次性创建整卷超高 Bitmap。
     * 首条带包含上边距、末条带包含下边距，内容与连续纸 PDF 输出一致。
     *
     * @param scale 每 pt 对应的像素数；输出条带宽度 = round(paper.widthPt * scale)
     * @return 是否完整渲染了全部条带（调用方返回 false 中止时为 false）
     */
    fun renderPrintStrips(
        markdown: String,
        paper: PlannerPaper,
        scale: Float,
        maxStripHeightPx: Int,
        consumer: PrintStripConsumer,
    ): Boolean {
        require(scale > 0f) { "scale must be positive" }
        require(maxStripHeightPx >= 64) { "maxStripHeightPx too small" }

        val palette = Palette()
        val margin = paper.marginPt
        val contentWidth = paper.widthPt - margin * 2
        val lines = layout(markdown, paper, contentWidth)
        val widthPx = round(paper.widthPt * scale).toInt()
        val maxStripPt = maxStripHeightPx / scale

        // 按行边界分组条带：条带高度（首条带含上边距）不超过像素上限
        class Strip(val lines: MutableList<LayoutLine>, val startPt: Float)
        val strips = mutableListOf<Strip>()
        var current = mutableListOf<LayoutLine>()
        var stripStartPt = 0f
        var cursor = margin   // 内容绘制光标；空白内容时停在 margin，条带自动退化为上下边距
        lines.forEach { line ->
            val required = line.height + line.spaceBefore
            val lineBottom = cursor + required
            val stripHeight = lineBottom - stripStartPt
            if (current.isNotEmpty() && stripHeight > maxStripPt) {
                strips.add(Strip(current, stripStartPt))
                current = mutableListOf()
                stripStartPt = cursor
            }
            current.add(line)
            cursor = lineBottom
        }
        val totalEndPt = cursor + margin  // 末条带尾部保留下边距
        strips.add(Strip(current, stripStartPt))

        strips.forEachIndexed { index, strip ->
            val endPt = if (index == strips.lastIndex) totalEndPt else strips[index + 1].startPt
            val pxStart = round(strip.startPt * scale).toInt()
            val pxHeight = round(endPt * scale).toInt() - pxStart
            if (pxHeight <= 0) return@forEachIndexed
            val continued = consumer.onStrip(index, widthPx, pxHeight) { canvas ->
                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(0f, -strip.startPt)
                var y = strip.startPt
                strip.lines.forEach { line ->
                    y += line.spaceBefore
                    drawLine(canvas, line, margin, contentWidth, y, palette)
                    y += line.height
                }
                canvas.restore()
            }
            if (!continued) return false
        }
        return true
    }

    fun interface PrintStripConsumer {
        /** 返回 false 中止后续条带（用户取消或发送失败时） */
        fun onStrip(index: Int, widthPx: Int, heightPx: Int, draw: (Canvas) -> Unit): Boolean
    }

    companion object {
        /** 连续纸最小页高（约 2 英寸），避免几乎空白的小纸条 */
        private const val MIN_CONTINUOUS_PAGE_PT = 144f

        /** PdfDocument 单页尺寸上限（200 英寸），超过则退化为分页 */
        private const val MAX_PDF_PAGE_PT = 14400f
    }

    // ---------------- 行模型 ----------------

    private class Seg(val text: String, val paint: TextPaint)

    private class LayoutLine(
        val segments: List<Seg>,
        val height: Float,
        val spaceBefore: Float = 0f,
        val indent: Float = 0f,
        val checkbox: Boolean? = null,   // null 无；true 已完成；false 未完成
        val isRule: Boolean = false,
        val quoteBar: Boolean = false,
        val mustFitExtra: Float = 0f,    // 标题行要求页尾预留空间，避免标题孤立在页底
    )

    // ---------------- 版面布局 ----------------

    private fun layout(markdown: String, paper: PlannerPaper, contentWidth: Float): List<LayoutLine> {
        val blocks = PlannerMarkdownParser.parse(markdown)
        val lines = mutableListOf<LayoutLine>()
        val font = paper.bodyFontPt
        val palette = Palette()

        blocks.forEach { block ->
            when (block) {
                is PlannerMarkdownParser.MdBlock.Heading -> {
                    val scale = when (block.level) {
                        1 -> 1.7f
                        2 -> 1.4f
                        else -> 1.2f
                    }
                    val size = font * scale
                    val paint = textPaint(palette.text, size, bold = true)
                    val spaceBefore = when (block.level) {
                        1 -> if (lines.isEmpty()) 0f else 16f
                        2 -> 12f
                        else -> 9f
                    }
                    // 段前距只作用于标题首行，避免折行后的行也带空隙
                    var isFirstLine = true
                    wrapRuns(block.runs, paint, contentWidth).forEach { segs ->
                        lines.add(
                            LayoutLine(
                                segments = segs,
                                height = size * 1.35f,
                                spaceBefore = if (isFirstLine) spaceBefore else 0f,
                                mustFitExtra = size * 2.2f,
                            )
                        )
                        isFirstLine = false
                    }
                }

                is PlannerMarkdownParser.MdBlock.Paragraph -> {
                    val paint = textPaint(palette.text, font, bold = false)
                    wrapRuns(block.runs, paint, contentWidth).forEach { segs ->
                        lines.add(LayoutLine(segments = segs, height = font * 1.5f))
                    }
                }

                is PlannerMarkdownParser.MdBlock.ListItem -> {
                    val paint = textPaint(palette.text, font, bold = false)
                    val donePaint = textPaint(palette.subtle, font, bold = false, strike = true)
                    val indent = 12f
                    val markerSeg: List<Seg> = when {
                        block.checked != null -> emptyList() // 复选框由绘制阶段处理
                        block.ordered -> listOf(Seg("${block.marker} ", paint))
                        else -> listOf(Seg("• ", paint))
                    }
                    val bodyPaint = if (block.checked == true) donePaint else paint
                    val wrapped = wrapRuns(block.runs, bodyPaint, contentWidth - indent - 14f)
                    wrapped.forEachIndexed { lineIndex, segs ->
                        val lineSegs = if (lineIndex == 0) markerSeg + segs else segs
                        lines.add(
                            LayoutLine(
                                segments = lineSegs,
                                height = font * 1.5f,
                                indent = if (lineIndex == 0) indent else indent + 12f,
                                checkbox = if (lineIndex == 0) block.checked else null,
                            )
                        )
                    }
                }

                is PlannerMarkdownParser.MdBlock.Quote -> {
                    val paint = textPaint(palette.subtle, font, bold = false)
                    wrapRuns(block.runs, paint, contentWidth - 14f).forEach { segs ->
                        lines.add(
                            LayoutLine(
                                segments = segs,
                                height = font * 1.5f,
                                indent = 14f,
                                quoteBar = true,
                            )
                        )
                    }
                }

                PlannerMarkdownParser.MdBlock.Rule -> {
                    lines.add(LayoutLine(segments = emptyList(), height = font * 0.8f, isRule = true, spaceBefore = 6f))
                }
            }
        }
        return lines
    }

    // ---------------- 绘制 ----------------

    private fun drawLine(
        canvas: Canvas,
        line: LayoutLine,
        margin: Float,
        contentWidth: Float,
        y: Float,
        palette: Palette,
    ) {
        if (line.isRule) {
            val paint = Paint().apply { color = palette.divider; strokeWidth = 1f }
            canvas.drawLine(margin, y + line.height / 2, margin + contentWidth, y + line.height / 2, paint)
            return
        }
        if (line.quoteBar) {
            val bar = Paint().apply { color = palette.divider }
            canvas.drawRect(margin, y + 1f, margin + 2.5f, y + line.height - 1f, bar)
        }
        var x = margin + line.indent
        if (line.checkbox != null) {
            val boxSize = line.height * 0.52f
            val boxTop = y + (line.height - boxSize) / 2
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.text
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            val rect = RectF(x, boxTop, x + boxSize, boxTop + boxSize)
            canvas.drawRoundRect(rect, 2f, 2f, boxPaint)
            if (line.checkbox) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text }
                canvas.drawRoundRect(rect, 2f, 2f, fill)
                val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 1.4f
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(rect.left + boxSize * 0.22f, rect.centerY(), rect.centerX(), rect.bottom - boxSize * 0.18f, check)
                canvas.drawLine(rect.centerX(), rect.bottom - boxSize * 0.18f, rect.right - boxSize * 0.15f, rect.top + boxSize * 0.2f, check)
            }
            x += boxSize + 5f
        }
        line.segments.forEach { seg ->
            if (seg.text.isEmpty()) return@forEach
            val fm = seg.paint.fontMetrics
            val baseline = y + line.height / 2 - (fm.ascent + fm.descent) / 2
            canvas.drawText(seg.text, x, baseline, seg.paint)
            x += seg.paint.measureText(seg.text)
        }
    }

    // ---------------- 文本工具 ----------------

    private fun textPaint(color: Int, size: Float, bold: Boolean, strike: Boolean = false): TextPaint {
        return TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            isStrikeThruText = strike
        }
    }

    /**
     * 把行内 run 序列按最大宽度折行为若干行。
     * 换行规则：CJK 字符可断行；拉丁字母/数字之间不断行（推迟到空格或标点）。
     */
    private fun wrapRuns(runs: List<PlannerMarkdownParser.MdRun>, paint: TextPaint, maxWidth: Float): List<List<Seg>> {
        val out = mutableListOf<List<Seg>>()
        var currentLine = mutableListOf<Seg>()
        var currentWidth = 0f

        fun newLine() {
            if (currentLine.isNotEmpty()) out.add(currentLine)
            currentLine = mutableListOf()
            currentWidth = 0f
        }

        fun appendText(text: String, bold: Boolean) {
            val segPaint = if (bold) {
                TextPaint(paint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            } else {
                paint
            }
            var buffer = StringBuilder()
            fun flushBuffer() {
                if (buffer.isNotEmpty()) {
                    // 与当前行最后一个同 paint 段合并
                    val last = currentLine.lastOrNull()
                    if (last != null && last.paint === segPaint) {
                        currentLine[currentLine.size - 1] = Seg(last.text + buffer.toString(), last.paint)
                    } else {
                        currentLine.add(Seg(buffer.toString(), segPaint))
                    }
                    buffer = StringBuilder()
                }
            }

            text.forEachIndexed { index, ch ->
                val candidateWidth = currentWidth + segPaint.measureText(ch.toString())
                val nextCh = if (index + 1 < text.length) text[index + 1] else ' '
                val inLatinRun = isLatinWordChar(ch) && isLatinWordChar(nextCh)
                if (candidateWidth > maxWidth && currentWidth > 0f && !inLatinRun) {
                    flushBuffer()
                    newLine()
                }
                buffer.append(ch)
                currentWidth += segPaint.measureText(ch.toString())
            }
            flushBuffer()
        }

        runs.forEach { run ->
            appendText(run.text, run.bold)
            // run 之间不主动断行，保持自然衔接
            if (currentLine.isEmpty()) return@forEach
        }
        if (out.isEmpty() && currentLine.isEmpty()) {
            // 空段落保留一个空行占位
            out.add(listOf(Seg("", paint)))
        } else if (currentLine.isNotEmpty()) {
            out.add(currentLine)
        }
        return out
    }

    private fun isLatinWordChar(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'
}
