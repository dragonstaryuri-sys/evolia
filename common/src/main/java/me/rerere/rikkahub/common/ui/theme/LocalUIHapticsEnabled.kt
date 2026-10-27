package me.rerere.rikkahub.common.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * UI 触感反馈是否启用。
 *
 * 由 app 模块的 Theme 在挂载时提供（与设置项联动），
 * 供 common 内的 rememberPremiumHaptics 等触感设施读取，
 * 使 discover 等库模块无需依赖 app 的 SettingsStore 即可获得统一触感。
 */
val LocalUIHapticsEnabled = staticCompositionLocalOf { true }
