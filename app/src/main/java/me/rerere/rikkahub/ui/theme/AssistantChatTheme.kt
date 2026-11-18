package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

/**
 * 聊天页的智能体动态主题。
 *
 * 规则（2026-11 确认）：
 * - 全局「动态颜色」开启 + 当前智能体有自己的聊天背景图 → 用该背景取色生成主题；
 * - 没有背景图 / 取色失败 / 全局动态颜色关闭 → 不覆盖，继承全局主题；
 * - 头像不再参与取色，也不再依赖智能体的「从角色取色」开关。
 */
@Composable
fun AssistantChatTheme(
    assistant: Assistant,
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()
    val context = LocalContext.current
    val darkTheme = LocalDarkMode.current

    val backgroundSource = resolveAssistantThemeBackground(
        dynamicColorEnabled = settings.dynamicColor,
        background = assistant.background,
    )

    // 缓存命中时首帧即可使用已提取的种子颜色，避免每次进入聊天页闪烁
    val initialSeedColor = remember(backgroundSource) {
        backgroundSource?.let { ThemeSeedColorCache.get(it) }
    }

    val seedColor by produceState<Color?>(initialValue = initialSeedColor, backgroundSource) {
        val source = backgroundSource
        if (source == null) {
            value = null
            return@produceState
        }
        value = extractSeedColorFromImage(context, source)
    }

    val seed = seedColor
    if (seed == null) {
        content()
        return
    }

    val scheme = buildDynamicColorScheme(
        seedColor = seed,
        darkTheme = darkTheme,
    ).withAmoledDarkCanvas(darkTheme)

    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
