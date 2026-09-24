package me.rerere.rikkahub.applock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * 生成应用锁定遮罩图片（9:16，webp 格式）。
 *
 * 视觉设计：
 * - 背景：低饱和度深墨绿（deep sage green），HSV(140, 0.25, 0.38)
 * - 字体：柚子 UI（GWMSansUI-Regular），从 assets/fonts 加载
 * - 文字：白色
 * - Emoji/颜文字：居中偏大显示，下方跟提示语
 */
object AppLockOverlayGenerator {

    private const val BASE_WIDTH = 1080
    private const val BASE_HEIGHT = 1920

    // 柚子 UI 字体缓存（避免每次生成都重新解码）
    @Volatile
    private var cachedTypeface: Typeface? = null

    /**
     * 加载柚子 UI 字体。失败回退到系统默认字体。
     * 字体文件位于 assets/fonts/GWMSansUI-Regular.ttf
     */
    private fun loadTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/GWMSansUI-Regular.ttf")
        }.getOrNull() ?: Typeface.DEFAULT
        cachedTypeface = tf
        return tf
    }

    /**
     * 生成遮罩图片并保存到内部存储。
     *
     * @param context 上下文
     * @param packageName 锁定的应用包名，用于命名文件
     * @param promptMessage 提示语
     * @param emoji 可选的颜文字或 emoji（只能一个）
     * @return 生成的图片文件路径，失败返回 null
     */
    fun generate(
        context: Context,
        packageName: String,
        promptMessage: String,
        emoji: String?
    ): String? {
        return runCatching {
            val bitmap = Bitmap.createBitmap(BASE_WIDTH, BASE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 背景：低饱和度深墨绿（HSV: hue=140, saturation=0.25, value=0.38）
            val bgColor = Color.HSVToColor(floatArrayOf(140f, 0.25f, 0.38f))
            canvas.drawColor(bgColor)

            // 加载柚子 UI 字体
            val typeface = loadTypeface(context)

            val centerX = BASE_WIDTH / 2f
            val contentTop = BASE_HEIGHT * 0.30f
            var currentY = contentTop

            // 绘制 emoji/颜文字
            if (!emoji.isNullOrBlank()) {
                val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 220f
                    textAlign = Paint.Align.CENTER
                    this.typeface = typeface
                }
                canvas.drawText(emoji, centerX, currentY + emojiPaint.textSize, emojiPaint)
                currentY += emojiPaint.textSize + 120f
            }

            // 绘制提示语
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 56f
                textAlign = Paint.Align.CENTER
                isSubpixelText = true
                this.typeface = typeface
            }
            val maxTextWidth = (BASE_WIDTH * 0.80f).toInt()
            val staticLayout = StaticLayout.Builder
                .obtain(promptMessage, 0, promptMessage.length, textPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.3f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(centerX, currentY)
            staticLayout.draw(canvas)
            canvas.restore()

            // 保存为 webp
            val dir = File(context.filesDir, "app_locks").apply { mkdirs() }
            val file = File(dir, "${sanitize(packageName)}.webp")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(format, 80, out)
            }
            bitmap.recycle()

            file.absolutePath
        }.getOrElse { e ->
            android.util.Log.e("AppLockOverlay", "生成遮罩图片失败", e)
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
