package me.rerere.rikkahub.common.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前应用是否处于深色模式。
 *
 * 由 app 模块的 Theme 在挂载时提供（与应用的颜色模式设置联动，而非系统设置），
 * 供 discover 等库模块在无法访问 MaterialTheme.extendColors 时
 * 进行深浅色成对的自定义配色（遵循 AGENTS 深浅色双模式规范）。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }
