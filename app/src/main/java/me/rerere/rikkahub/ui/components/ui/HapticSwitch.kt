package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

/**
 * 带有触感反馈且经过响应速度优化的 Material 3 开关。
 *
 * 通过局部状态管理确保滑块即时移动，解决 DataStore 写入延迟导致的“卡顿”感。
 */
@Composable
fun HapticSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors()
) {
    val haptics = rememberPremiumHaptics()

    // 使用局部状态提供即时视觉反馈
    var localChecked by remember(checked) { mutableStateOf(checked) }

    Switch(
        checked = localChecked,
        onCheckedChange = { newValue ->
            if (onCheckedChange != null) {
                localChecked = newValue // 立即移动滑块
                haptics.perform(HapticPattern.Pop)
                onCheckedChange.invoke(newValue)
            }
        },
        modifier = modifier,
        thumbContent = thumbContent,
        enabled = enabled,
        colors = colors
    )
}
