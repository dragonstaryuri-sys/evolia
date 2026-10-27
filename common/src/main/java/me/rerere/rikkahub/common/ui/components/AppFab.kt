package me.rerere.rikkahub.common.ui.components

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes

/** FAB 视觉变体：主操作（主题默认色）/ 次级操作（中性灰）。 */
enum class FabVariant { Primary, Secondary }

/**
 * 通用 FAB：统一 CardLarge 形状与 PremiumHaptics 触感。
 * 收敛自「AppShapes.CardLarge 手写摆放」「CircleShape + 系统触感」「次级灰写死」三种并行实现。
 */
@Composable
fun AppFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FabVariant = FabVariant.Primary,
    content: @Composable () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val isSecondary = variant == FabVariant.Secondary
    val containerColor = if (isSecondary) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        FloatingActionButtonDefaults.containerColor
    }
    val contentColor = if (isSecondary) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    FloatingActionButton(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            onClick()
        },
        modifier = modifier,
        shape = AppShapes.CardLarge,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content,
    )
}
