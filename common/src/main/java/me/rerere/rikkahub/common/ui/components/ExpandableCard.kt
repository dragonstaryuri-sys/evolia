package me.rerere.rikkahub.common.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 通用可折叠卡片组件
 *
 * @param title 标题
 * @param modifier 修饰符
 * @param expanded 受控模式：由调用方持有展开状态（LazyColumn item 会被回收时必须使用，
 * 内部 remember 状态会随 item dispose 丢失）
 * @param initiallyExpanded 非受控模式：内部 remember 持有状态时的初始值
 * @param onExpandedChange 受控模式下的展开状态变更回调
 * @param containerColor 背景色
 * @param content 展开后的内容
 */
@Composable
fun ExpandableCard(
    title: String,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    initiallyExpanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    val isControlled = expanded != null && onExpandedChange != null
    var internalExpanded by remember { mutableStateOf(initiallyExpanded) }
    // 命名变量：受控模式用调用方状态，非受控模式用内部状态
    val currentExpanded = expanded ?: internalExpanded
    val haptic = LocalHapticFeedback.current

    // 旋转动画
    val rotationState by animateFloatAsState(
        targetValue = if (currentExpanded) 0f else -90f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessLow
        ),
        label = "Rotation"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isControlled) {
                            onExpandedChange?.invoke(!currentExpanded)
                        } else {
                            internalExpanded = !currentExpanded
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotationState),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Content
            AnimatedVisibility(
                visible = currentExpanded,
                enter = expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    expandFrom = Alignment.Top
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    shrinkTowards = Alignment.Top
                ) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
