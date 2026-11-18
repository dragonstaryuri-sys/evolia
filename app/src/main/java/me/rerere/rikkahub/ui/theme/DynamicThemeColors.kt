package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL

/**
 * 动态主题（图片取色）通用模块。
 *
 * 职责拆分为两部分，供「全局动态颜色」与「聊天页智能体主题」共用：
 * 1. [extractSeedColorFromImage]：图片 -> 种子颜色；
 * 2. [buildDynamicColorScheme]：种子颜色 -> 完整的「预设主题级」ColorScheme。
 *
 * 两套主题的数据来源保持独立：全局主题只读主智能体聊天背景图，
 * 聊天页主题只读当前智能体自己的聊天背景图；头像不参与取色。
 */

/** Palette 取色前将图片缩放到的最长边尺寸，避免直接解码超大原图。 */
private const val PALETTE_TARGET_SIZE = 128

/** 种子颜色内存缓存容量（按图片数量计）。 */
private const val SEED_CACHE_MAX_ENTRIES = 8

/** 低于该饱和度视为无法提取有效主题色（近乎纯灰的图片）。 */
private const val MIN_SEED_SATURATION = 0.08f

/** AMOLED 深色模式下强制纯黑的背景/表面色。 */
internal val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

/**
 * 图片种子颜色缓存。
 *
 * key 为图片来源（content:// / file:// / android.resource:// / http(s):// / 本地文件路径）。
 * 聊天背景由 BackgroundPicker 落盘到 `files/upload/` 且每次选择都会生成新的 Uuid 文件名，
 * 因此「换图 / 删图」必然改变 URI，旧条目不会被误用到新图片上。
 * 远程 URL 若同名覆盖资源内容，进程内缓存不会感知（重进应用后刷新）。
 */
object ThemeSeedColorCache {
    private val entries = object : LinkedHashMap<String, Color>(SEED_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>): Boolean {
            return size > SEED_CACHE_MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(source: String): Color? = entries[source]

    @Synchronized
    fun put(source: String, color: Color) {
        entries[source] = color
    }
}

/**
 * 从图片中提取代表性种子颜色。
 *
 * 支持 content://、file://、android.resource://、http(s):// 与本地文件路径；
 * 解码与 Palette 计算在 [Dispatchers.IO] 执行。加载失败、格式不支持或无有效颜色时返回 null。
 */
suspend fun extractSeedColorFromImage(context: Context, source: String): Color? {
    ThemeSeedColorCache.get(source)?.let { return it }
    val color = withContext(Dispatchers.IO) {
        extractSeedColorFromImageBlocking(context.applicationContext, source)
    }
    color?.let { ThemeSeedColorCache.put(source, it) }
    return color
}

/**
 * 解析聊天页是否应该用「当前智能体自己的聊天背景」生成独立主题。
 *
 * 规则（2026-11 确认）：
 * - 全局「动态颜色」关闭时，聊天页跟随全局预设主题，不做独立取色；
 * - 全局开启且当前智能体存在聊天背景图 → 用该背景取色；
 * - 没有背景图（或取色失败）由调用方继承全局主题，头像不再参与取色。
 */
internal fun resolveAssistantThemeBackground(
    dynamicColorEnabled: Boolean,
    background: String?,
): String? {
    if (!dynamicColorEnabled) return null
    return background
}

/**
 * 根据种子颜色生成一整套「预设主题级」的 Material 3 ColorScheme。
 *
 * 版式与 `ui/theme/presets` 下的预设主题一致：
 * - 背景、表面、正文文字使用纯中性灰阶（浅色近白/近黑，深色深灰/近白），不随图片染色；
 * - 只有 primary / secondary / tertiary 及其 container 由种子色相派生
 *   （secondary 降饱和，tertiary 色相偏移 +45°，容器保持同色相的明/暗调）；
 * - error 系沿用 M3 标准红。
 *
 * 因此切换图片时，变化的只有强调色相关部位，正文与卡片观感与预设主题一致。
 */
fun buildDynamicColorScheme(
    seedColor: Color,
    darkTheme: Boolean
): ColorScheme {
    return if (darkTheme) {
        buildDynamicDarkScheme(seedColor)
    } else {
        buildDynamicLightScheme(seedColor)
    }
}

private fun buildDynamicLightScheme(seedColor: Color): ColorScheme {
    val seed = seedColor.toHsl()
    val hue = seed.hue
    val seedSaturation = seed.saturation

    val primarySaturation = seedSaturation.coerceIn(0.32f, 0.65f)
    val primary = hsl(hue, primarySaturation, seed.lightness.coerceIn(0.30f, 0.42f))
    val primaryContainer = hsl(hue, seedSaturation.coerceIn(0.55f, 1f), 0.90f)
    val onPrimaryContainer = hsl(hue, seedSaturation.coerceIn(0.30f, 0.60f), 0.28f)

    val secondarySaturation = (seedSaturation * 0.35f).coerceIn(0.10f, 0.20f)
    val secondary = hsl(hue, secondarySaturation, 0.38f)
    val secondaryContainer = hsl(hue, (seedSaturation * 0.30f).coerceIn(0.10f, 0.25f), 0.91f)
    val onSecondaryContainer = hsl(hue, secondarySaturation, 0.26f)

    val tertiaryHue = (hue + 45f) % 360f
    val tertiarySaturation = (seedSaturation * 0.35f).coerceIn(0.10f, 0.22f)
    val tertiary = hsl(tertiaryHue, tertiarySaturation, 0.40f)
    val tertiaryContainer = hsl(tertiaryHue, (seedSaturation * 0.25f).coerceIn(0.10f, 0.20f), 0.92f)
    val onTertiaryContainer = hsl(tertiaryHue, tertiarySaturation, 0.28f)

    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = hsl(hue, primarySaturation, 0.78f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = Color.White,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightBackground,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        scrim = Color.Black,
        inverseSurface = LightInverseSurface,
        inverseOnSurface = LightInverseOnSurface,
        surfaceDim = LightSurfaceDim,
        surfaceBright = LightSurfaceBright,
        surfaceContainerLowest = LightSurfaceContainerLowest,
        surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
    )
}

private fun buildDynamicDarkScheme(seedColor: Color): ColorScheme {
    val seed = seedColor.toHsl()
    val hue = seed.hue
    val seedSaturation = seed.saturation

    val primary = hsl(hue, seedSaturation.coerceIn(0.30f, 0.60f), 0.76f)
    val onPrimary = hsl(hue, seedSaturation.coerceIn(0.30f, 0.50f), 0.18f)
    val primaryContainer = hsl(hue, seedSaturation.coerceIn(0.25f, 0.45f), 0.34f)
    val onPrimaryContainer = hsl(hue, seedSaturation.coerceIn(0.25f, 0.50f), 0.90f)

    val secondarySaturation = (seedSaturation * 0.55f).coerceIn(0.20f, 0.35f)
    val secondary = hsl(hue, secondarySaturation, 0.80f)
    val secondaryContainer = hsl(hue, (seedSaturation * 0.45f).coerceIn(0.18f, 0.32f), 0.32f)
    val onSecondaryContainer = hsl(hue, secondarySaturation, 0.90f)

    val tertiaryHue = (hue + 45f) % 360f
    val tertiary = hsl(tertiaryHue, secondarySaturation, 0.82f)
    val tertiaryContainer = hsl(tertiaryHue, (seedSaturation * 0.40f).coerceIn(0.16f, 0.30f), 0.33f)
    val onTertiaryContainer = hsl(tertiaryHue, secondarySaturation, 0.92f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = hsl(hue, seedSaturation.coerceIn(0.32f, 0.65f), 0.38f),
        secondary = secondary,
        onSecondary = hsl(hue, secondarySaturation, 0.18f),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = hsl(tertiaryHue, secondarySaturation, 0.18f),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkBackground,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        scrim = Color.Black,
        inverseSurface = DarkInverseSurface,
        inverseOnSurface = DarkInverseOnSurface,
        surfaceDim = DarkSurfaceDim,
        surfaceBright = DarkSurfaceBright,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
    )
}

/** AMOLED 深色模式：背景与表面统一为纯黑，其他颜色沿用传入方案。 */
fun ColorScheme.withAmoledDarkCanvas(darkTheme: Boolean): ColorScheme {
    if (!darkTheme) return this
    return copy(
        background = AMOLED_DARK_BACKGROUND,
        surface = AMOLED_DARK_BACKGROUND,
    )
}

/**
 * 是否为可用的主题种子色。
 *
 * 近乎纯灰的图片（饱和度 < 8%）无法定义有意义的主题色相，
 * 按「取不到有效颜色」处理，交由调用方回退预设主题。
 */
internal fun Color.isUsableThemeSeed(): Boolean = toHsl().saturation >= MIN_SEED_SATURATION

// region HSL helpers

private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val normalizedHue = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((normalizedHue / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        normalizedHue < 60f -> Triple(c, x, 0f)
        normalizedHue < 120f -> Triple(x, c, 0f)
        normalizedHue < 180f -> Triple(0f, c, x)
        normalizedHue < 240f -> Triple(0f, x, c)
        normalizedHue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = 1f)
}

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) {
        return Hsl(hue = 0f, saturation = 0f, lightness = lightness)
    }
    val saturation = if (lightness > 0.5f) {
        delta / (2f - max - min)
    } else {
        delta / (max + min)
    }
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsl(
        hue = (hue + 360f) % 360f,
        saturation = saturation,
        lightness = lightness,
    )
}

// endregion

// region 中性色与语义色（不随种子色变化）

private val LightBackground = Color(0xFFFCFCFC)
private val LightOnSurface = Color(0xFF1B1B1D)
private val LightSurfaceVariant = Color(0xFFE4E4E7)
private val LightOnSurfaceVariant = Color(0xFF46464A)
private val LightOutline = Color(0xFF77777B)
private val LightOutlineVariant = Color(0xFFC8C8CC)
private val LightInverseSurface = Color(0xFF303033)
private val LightInverseOnSurface = Color(0xFFF1F1F1)
private val LightSurfaceDim = Color(0xFFDADADA)
private val LightSurfaceBright = Color(0xFFFCFCFC)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF7F7F7)
private val LightSurfaceContainer = Color(0xFFF1F1F1)
private val LightSurfaceContainerHigh = Color(0xFFEBEBEB)
private val LightSurfaceContainerHighest = Color(0xFFE5E5E5)

private val DarkBackground = Color(0xFF121212)
private val DarkOnSurface = Color(0xFFE6E6E6)
private val DarkSurfaceVariant = Color(0xFF46464A)
private val DarkOnSurfaceVariant = Color(0xFFC8C8CC)
private val DarkOutline = Color(0xFF909095)
private val DarkOutlineVariant = Color(0xFF46464A)
private val DarkInverseSurface = Color(0xFFE6E6E6)
private val DarkInverseOnSurface = Color(0xFF303033)
private val DarkSurfaceDim = Color(0xFF0D0D0D)
private val DarkSurfaceBright = Color(0xFF383838)
private val DarkSurfaceContainerLowest = Color(0xFF080808)
private val DarkSurfaceContainerLow = Color(0xFF0F0F0F)
private val DarkSurfaceContainer = Color(0xFF141414)
private val DarkSurfaceContainerHigh = Color(0xFF1E1E1E)
private val DarkSurfaceContainerHighest = Color(0xFF282828)

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF93000A)

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

// endregion

private fun extractSeedColorFromImageBlocking(context: Context, source: String): Color? {
    val bitmap = loadBitmap(context, source) ?: return null
    return extractSeedColorFromBitmap(bitmap)
}

private fun extractSeedColorFromBitmap(bitmap: Bitmap): Color? {
    val scaled = scaleBitmap(bitmap, PALETTE_TARGET_SIZE)
    return try {
        val palette = Palette.from(scaled).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.lightMutedSwatch
            ?: palette.darkMutedSwatch
        val seed = swatch?.rgb?.let { Color(it) } ?: return null
        // 近乎纯灰的色板无法定义主题色相，按取色失败处理
        seed.takeIf { it.isUsableThemeSeed() }
    } catch (e: Exception) {
        null
    } finally {
        // 缩放后可能返回同一个对象，避免重复 recycle 触发异常
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        scaled.recycle()
    }
}

private fun scaleBitmap(bitmap: Bitmap, targetSize: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= targetSize && height <= targetSize) {
        return bitmap
    }
    val scale = targetSize.toFloat() / maxOf(width, height).toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
}

private fun loadBitmap(context: Context, source: String): Bitmap? {
    return runCatching {
        val uri = Uri.parse(source)
        when (uri.scheme) {
            "content", "file", "android.resource" -> {
                decodeBitmap {
                    context.contentResolver.openInputStream(uri)
                }
            }

            "http", "https" -> {
                decodeBitmap {
                    val connection = URL(source).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    connection.getInputStream()
                }
            }

            null -> {
                File(source).takeIf { it.exists() }?.let { file ->
                    decodeBitmap {
                        file.inputStream()
                    }
                }
            }

            else -> null
        }
    }.getOrNull()
}

private fun decodeBitmap(openStream: () -> InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    openStream()?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, PALETTE_TARGET_SIZE)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    return openStream()?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}

private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
    var sampleSize = 1
    var halfWidth = width / 2
    var halfHeight = height / 2
    while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}
