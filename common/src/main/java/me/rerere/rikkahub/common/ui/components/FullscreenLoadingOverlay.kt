package me.rerere.rikkahub.common.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆形进度圈 + 中间居中图标
 *
 * @param icon         中心显示的图标矢量，默认是 Description（文档图标）
 * @param iconTint     图标和进度圈的颜色，默认使用主题 primary
 * @param overallSize  整个组件的外尺寸（圆形框大小）
 * @param iconSize     中央图标的尺寸
 * @param strokeWidth  进度圈的线宽
 */
@Composable
fun CircularIconProgressIndicator(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Description,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    overallSize: Dp = 120.dp,
    iconSize: Dp = 54.dp,
    strokeWidth: Dp = 3.dp,
) {
    Box(
        modifier = modifier.size(overallSize),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            color = iconTint,
            strokeWidth = strokeWidth,
            trackColor = iconTint.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = iconTint
        )
    }
}

/**
 * 全屏加载蒙板：呼吸渐隐背景 + 垂直居中的 (圆形动画, 提示文字)
 *
 * 适用于"请在此页面耐心等待、不要离开"的阻塞性场景，例如：
 * - 会话归档 / 上下文同步（ChatPage）
 * - 手写日记 webp 压缩并落盘保存（DiaryEditorPage）
 *
 * @param visible      是否显示（通常绑定 `isLoading` 状态）
 * @param icon         中央动画里的图标
 * @param hint         下方显示的提示文字
 * @param iconTint     图标和进度圈颜色
 * @param hintStyle    提示文字的 TextStyle，默认 bodyLarge
 */
@Composable
fun FullscreenLoadingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Description,
    hint: String = "",
    iconTint: Color = MaterialTheme.colorScheme.primary,
    hintStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "overlay_breathing")
        val breathingAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing_alpha"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = breathingAlpha))
                .clickable(enabled = true, onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularIconProgressIndicator(
                    modifier = Modifier.padding(bottom = 24.dp),
                    icon = icon,
                    iconTint = iconTint
                )
                Text(
                    text = hint,
                    style = hintStyle,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
