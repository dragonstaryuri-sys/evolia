package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 动态主题配色生成的纯逻辑验证。
 *
 * 口径：动态色板按预设主题的版式整套生成——中性表面/正文不随图片变化，
 * 仅 primary / secondary / tertiary 及其容器由种子色相派生；
 * 樱花粉只作为取色失败时的回退（由 Theme.kt 负责）。
 */
class DynamicThemeColorsTest {

    private val greenSeed = Color(0xFF3F7F6A)
    private val blueSeed = Color(0xFF2E5FA8)

    @Test
    fun `light scheme keeps neutral surfaces and text`() {
        val scheme = buildDynamicColorScheme(greenSeed, darkTheme = false)

        assertEquals(Color(0xFFFCFCFC), scheme.background)
        assertEquals(Color(0xFFFCFCFC), scheme.surface)
        assertEquals(Color(0xFF1B1B1D), scheme.onSurface)
        assertEquals(Color(0xFF1B1B1D), scheme.onBackground)
        assertEquals(Color(0xFFEBEBEB), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFF1F1F1), scheme.surfaceContainer)
        assertEquals(Color(0xFFBA1A1A), scheme.error)
    }

    @Test
    fun `light accents derive from seed hue`() {
        val scheme = buildDynamicColorScheme(greenSeed, darkTheme = false)
        val seedHsl = greenSeed.toHsl()
        val primaryHsl = scheme.primary.toHsl()

        assertEquals(seedHsl.hue, primaryHsl.hue, 2f)
        assertTrue(primaryHsl.lightness in 0.30f..0.42f)
        assertEquals(Color.White, scheme.onPrimary)
    }

    @Test
    fun `light containers keep seed hue with light tone`() {
        val scheme = buildDynamicColorScheme(greenSeed, darkTheme = false)
        val seedHue = greenSeed.toHsl().hue
        val container = scheme.primaryContainer.toHsl()

        assertEquals(seedHue, container.hue, 2f)
        assertTrue(container.lightness > 0.85f)
        assertTrue(scheme.primaryContainer.luminance() > 0.75f)
    }

    @Test
    fun `secondary is less saturated than primary and tertiary shifts hue`() {
        val scheme = buildDynamicColorScheme(greenSeed, darkTheme = false)
        val seedHue = greenSeed.toHsl().hue

        assertTrue(scheme.secondary.toHsl().saturation < scheme.primary.toHsl().saturation)
        assertEquals((seedHue + 45f) % 360f, scheme.tertiary.toHsl().hue, 2f)
    }

    @Test
    fun `dark scheme keeps neutral surfaces and pastel accents`() {
        val scheme = buildDynamicColorScheme(greenSeed, darkTheme = true)
        val seedHue = greenSeed.toHsl().hue

        assertEquals(Color(0xFF121212), scheme.background)
        assertEquals(Color(0xFFE6E6E6), scheme.onSurface)
        assertEquals(Color(0xFF1E1E1E), scheme.surfaceContainerHigh)
        assertEquals(seedHue, scheme.primary.toHsl().hue, 2f)
        assertEquals(0.76f, scheme.primary.toHsl().lightness, 0.01f)
        assertTrue(scheme.onPrimary.toHsl().lightness < 0.30f)
    }

    @Test
    fun `different seeds do not affect neutral surfaces`() {
        val green = buildDynamicColorScheme(greenSeed, darkTheme = false)
        val blue = buildDynamicColorScheme(blueSeed, darkTheme = false)

        assertEquals(green.background, blue.background)
        assertEquals(green.onSurface, blue.onSurface)
        assertEquals(green.surfaceContainerHigh, blue.surfaceContainerHigh)
        assertEquals(green.outline, blue.outline)
        // 但强调色必须随种子变化
        assertTrue(green.primary != blue.primary)
        assertTrue(green.primaryContainer != blue.primaryContainer)
    }

    @Test
    fun `amoled dark canvas forces pure black background and surface`() {
        val dark = buildDynamicColorScheme(greenSeed, darkTheme = true)
        val amoled = dark.withAmoledDarkCanvas(darkTheme = true)

        assertEquals(Color(0xFF000000), amoled.background)
        assertEquals(Color(0xFF000000), amoled.surface)
        // 其他中性层级不受 AMOLED 处理影响
        assertEquals(dark.surfaceContainerHigh, amoled.surfaceContainerHigh)
        assertEquals(dark.primary, amoled.primary)
    }

    @Test
    fun `amoled canvas is a no-op in light mode`() {
        val light = buildDynamicColorScheme(greenSeed, darkTheme = false)

        assertEquals(light, light.withAmoledDarkCanvas(darkTheme = false))
    }

    @Test
    fun `grayish seed is treated as invalid theme color`() {
        assertFalse(Color(0xFF808080).isUsableThemeSeed())
        assertFalse(Color(0xFF101010).isUsableThemeSeed())
        assertTrue(greenSeed.isUsableThemeSeed())
    }

    @Test
    fun `chat theme follows own background only when global dynamic color is on`() {
        val background = "file:///data/user/0/app/files/upload/assistant-bg"

        assertEquals(background, resolveAssistantThemeBackground(true, background))
        // 全局动态颜色关闭 → 聊天页跟随预设主题
        assertNull(resolveAssistantThemeBackground(false, background))
        // 智能体没有自己的背景 → 继承全局主题
        assertNull(resolveAssistantThemeBackground(true, null))
    }

    @Test
    fun `seed cache is isolated per image source`() {
        val sourceA = "file:///tmp/theme-a.webp"
        val sourceB = "file:///tmp/theme-b.webp"
        val colorA = Color(0xFFAA3344)

        ThemeSeedColorCache.put(sourceA, colorA)

        assertEquals(colorA, ThemeSeedColorCache.get(sourceA))
        assertNull(ThemeSeedColorCache.get(sourceB))
    }
}

private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) {
        return Hsl(0f, 0f, lightness)
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
    return Hsl((hue + 360f) % 360f, saturation, lightness)
}

private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
    assertTrue(
        "expected $expected but was $actual",
        abs(expected - actual) <= tolerance,
    )
}
