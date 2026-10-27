package me.rerere.rikkahub.common.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 行内加载指示器，统一各页面手抄的 6 种尺寸为两档：
 * - 默认 24dp（按钮/列表行内）
 * - 紧凑 16dp（紧凑徽章、小型控件内）
 * [color] 为 null 时使用 M3 默认（primary）。
 */
@Composable
fun InlineSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Dp = 3.dp,
    color: Color? = null,
) {
    if (color != null) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = strokeWidth,
            color = color,
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = strokeWidth,
        )
    }
}
