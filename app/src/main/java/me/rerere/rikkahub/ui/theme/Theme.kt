package me.rerere.rikkahub.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.getMainAssistant
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.ui.theme.presets.SakuraThemePreset

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

    // 全局动态颜色：不再使用系统壁纸，只取主智能体的聊天背景图。
    // 主智能体没有背景图、图片加载失败或取色失败时，统一回退到樱花粉预设主题。
    val mainAssistantBackground = if (settings.dynamicColor) {
        settings.getMainAssistant()?.background
    } else {
        null
    }
    val context = LocalContext.current
    val seedColor by produceState<Color?>(
        initialValue = mainAssistantBackground?.let { ThemeSeedColorCache.get(it) },
        mainAssistantBackground,
    ) {
        val source = mainAssistantBackground
        if (source == null) {
            value = null
            return@produceState
        }
        value = extractSeedColorFromImage(context, source)
    }

    val colorScheme = when {
        settings.dynamicColor -> {
            // 樱花粉只作为「无背景图 / 取色失败」的回退，不参与配色生成
            seedColor?.let { seed ->
                buildDynamicColorScheme(
                    seedColor = seed,
                    darkTheme = darkTheme,
                )
            } ?: SakuraThemePreset.getColorScheme(dark = darkTheme)
        }

        darkTheme -> findPresetTheme(settings.themeId).getColorScheme(dark = true)
        else -> findPresetTheme(settings.themeId).getColorScheme(dark = false)
    }
    val colorSchemeConverted = remember(darkTheme, colorScheme) {
        colorScheme.withAmoledDarkCanvas(darkTheme)
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        me.rerere.rikkahub.common.ui.theme.LocalDarkTheme provides darkTheme,
        me.rerere.rikkahub.common.ui.theme.LocalUIHapticsEnabled provides settings.displaySetting.enableUIHaptics
    ) {
        // Create typography from font settings
        val fontSettings = me.rerere.rikkahub.ui.hooks.rememberFontSettings()
        val typography = rememberTypographyFromFontSettings(fontSettings)
        
        MaterialTheme(
            colorScheme = colorSchemeConverted,
            typography = typography,
            shapes = Shapes,
            content = content
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current

/**
 * 设置分组容器的标准背景色（暗色 surfaceContainerLow / 亮色 surfaceContainerHigh）。
 * 统一收敛自 SettingGroupItem、AssistantSettingsItem 等处复制粘贴的三元公式，
 * 新代码禁止再内联该判断。
 */
@Composable
fun groupContainerColor(): Color = if (LocalDarkMode.current) {
    MaterialTheme.colorScheme.surfaceContainerLow
} else {
    MaterialTheme.colorScheme.surfaceContainerHigh
}
