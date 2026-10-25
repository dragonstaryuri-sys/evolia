package me.rerere.rikkahub.data.planner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * 日计划文档输出：
 * - PDF：android.graphics.pdf.PdfDocument，页面按 pt 1:1 绘制，中文字体使用系统字体；
 * - 预览 PNG：同一渲染结果放大绘制为位图（首页），供对话内确认纸张版面。
 * 预览与导出使用完全相同的渲染结果。
 */
object PlannerDocumentExporter {

    /** 渲染 PDF 到目标文件，返回页数 */
    fun exportPdf(
        renderer: PlannerMarkdownPdfRenderer,
        markdown: String,
        paper: PlannerPaper,
        landscape: Boolean,
        outFile: File,
    ): Int {
        val document = PdfDocument()
        try {
            var pages = 0
            renderer.render(markdown, paper, landscape) { index, widthPt, heightPt, draw ->
                val info = PdfDocument.PageInfo
                    .Builder(widthPt, heightPt, index + 1)
                    .create()
                val page = document.startPage(info)
                draw(page.canvas)
                document.finishPage(page)
                pages++
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { document.writeTo(it) }
            return pages
        } finally {
            document.close()
        }
    }

    /** 渲染首页预览 PNG，返回文件（渲染失败返回 null） */
    fun renderPreviewPng(
        renderer: PlannerMarkdownPdfRenderer,
        markdown: String,
        paper: PlannerPaper,
        landscape: Boolean,
        outDir: File,
    ): File? {
        outDir.mkdirs()
        var result: File? = null
        val timestamp = System.currentTimeMillis()
        // 预览按内容宽度放大到约 720px，窄纸条用更高倍率保证可读
        val previewTargetPx = 720f
        renderer.render(markdown, paper, landscape) { index, widthPt, heightPt, draw ->
            if (result != null) return@render
            // 宽度放大到约 720px；连续纸页高随内容增长，需按高度钳制倍率防止超大位图
            val scale = minOf(
                previewTargetPx / widthPt,
                MAX_PREVIEW_HEIGHT_PX / heightPt
            ).coerceIn(1.5f, 6f)
            val bitmap = Bitmap.createBitmap(
                (widthPt * scale).toInt(),
                (heightPt * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            draw(canvas)
            val file = File(outDir, "plan_${timestamp}_p${index + 1}.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            result = file
        }
        return result
    }

    private const val MAX_PREVIEW_HEIGHT_PX = 4200f
}
