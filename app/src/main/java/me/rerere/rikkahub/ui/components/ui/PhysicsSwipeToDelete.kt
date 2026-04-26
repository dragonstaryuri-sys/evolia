package me.rerere.rikkahub.ui.components.ui

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Position of item in a group for corner radius calculation
 */
enum class ItemPosition {
    ONLY,   // Only item in group - all corners rounded
    FIRST,  // First item - top corners rounded
    MIDDLE, // Middle item - no corners rounded
    LAST    // Last item - bottom corners rounded
}

@Composable
fun PhysicsSwipeToDelete(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    position: ItemPosition = ItemPosition.ONLY,
    groupCornerRadius: Dp = 24.dp,
    itemCornerRadius: Dp = 0.dp,
    neighborOffset: Float = 0f,
    onDragProgress: ((offset: Float, isUnlocked: Boolean) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    content: @Composable (shape: Shape) -> Unit
) {
    val density = LocalDensity.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    val dragFriction = if (deleteEnabled) 0.6f else 0.15f
    val revealDistancePx = with(density) { 80.dp.toPx() }
    val unlockThresholdPx = revealDistancePx * 0.4f
    val magneticPullStrength = 0.3f

    val offsetX = remember { Animatable(0f) }
    var isUnlocked by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(deleteEnabled) {
        if (!deleteEnabled) {
            offsetX.snapTo(0f)
            isUnlocked = false
            isDragging = false
        }
    }

    LaunchedEffect(offsetX.value, isUnlocked, isDragging) {
        if (isDragging && !isUnlocked) {
            onDragProgress?.invoke(offsetX.value, isUnlocked)
        }
    }

    val animatedNeighborOffset = remember { Animatable(0f) }
    var wasNeighborInfluenced by remember { mutableStateOf(false) }

    LaunchedEffect(neighborOffset) {
        if (neighborOffset != 0f) {
            animatedNeighborOffset.snapTo(neighborOffset)
            wasNeighborInfluenced = true
        } else if (wasNeighborInfluenced) {
            wasNeighborInfluenced = false
            animatedNeighborOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 1000f)
            )
        }
    }

    val totalOffset = offsetX.value + animatedNeighborOffset.value
    val unlockProgress by remember {
        derivedStateOf {
            (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f)
        }
    }

    val groupRadiusPx = with(density) { groupCornerRadius.toPx() }
    val itemRadiusPx = with(density) { itemCornerRadius.toPx() }

    val targetTopRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.FIRST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.LAST -> itemRadiusPx
    }
    val targetBottomRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.LAST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.FIRST -> itemRadiusPx
    }

    val animatedTopRadius by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetTopRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "topRadius"
    )
    val animatedBottomRadius by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetBottomRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "bottomRadius"
    )

    val shape by remember {
        derivedStateOf {
            val ownUnlockProgress = if (neighborOffset == 0f) unlockProgress else 0f
            val finalTopStart = animatedTopRadius + (groupRadiusPx - animatedTopRadius) * ownUnlockProgress
            val finalTopEnd = animatedTopRadius + (groupRadiusPx - animatedTopRadius) * ownUnlockProgress
            val finalBottomEnd = animatedBottomRadius + (groupRadiusPx - animatedBottomRadius) * ownUnlockProgress
            val finalBottomStart = animatedBottomRadius + (groupRadiusPx - animatedBottomRadius) * ownUnlockProgress

            RoundedCornerShape(
                topStart = with(density) { finalTopStart.toDp() },
                topEnd = with(density) { finalTopEnd.toDp() },
                bottomEnd = with(density) { finalBottomEnd.toDp() },
                bottomStart = with(density) { finalBottomStart.toDp() }
            )
        }
    }

    // 对调：卡片颜色改为浅灰 (surfaceContainerHigh)，以便与白色背景区分，与设置页保持一致
    val cardColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.matchParentSize().padding(end = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (deleteEnabled) {
                PhysicsSwipeActionButton(
                    onClick = {
                        haptics.perform(HapticPattern.Error)
                        onDelete()
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    alpha = (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f)
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(totalOffset.roundToInt(), 0) }
                .clip(shape)
                .background(cardColor)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            onDragEnd?.invoke()
                            scope.launch {
                                if (!deleteEnabled) {
                                    if (offsetX.value.absoluteValue > 10f) haptics.perform(HapticPattern.Thud)
                                    offsetX.animateTo(0f, spring(0.55f, Spring.StiffnessMediumLow))
                                } else if (offsetX.value.absoluteValue > unlockThresholdPx) {
                                    if (!isUnlocked) { haptics.perform(HapticPattern.Pop); isUnlocked = true }
                                    offsetX.animateTo(-revealDistancePx, spring(0.6f, 300f))
                                } else {
                                    if (isUnlocked) { haptics.perform(HapticPattern.Thud); isUnlocked = false }
                                    offsetX.animateTo(0f, spring(0.55f, Spring.StiffnessLow))
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch { offsetX.animateTo(if (isUnlocked) -revealDistancePx else 0f, spring(0.6f)) }
                            onDragEnd?.invoke()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val currentOffset = offsetX.value
                            if (dragAmount < 0 || currentOffset < 0) {
                                val friction = if (currentOffset.absoluteValue < unlockThresholdPx && !isUnlocked) {
                                    dragFriction * (1f - magneticPullStrength * (currentOffset.absoluteValue / unlockThresholdPx))
                                } else dragFriction
                                val newOffset = (currentOffset + dragAmount * friction).coerceIn(-revealDistancePx * 1.5f, 0f)
                                scope.launch { offsetX.snapTo(newOffset) }
                                if (currentOffset.absoluteValue < unlockThresholdPx && newOffset.absoluteValue >= unlockThresholdPx && !isUnlocked) {
                                    haptics.perform(HapticPattern.Pop)
                                } else if (currentOffset.absoluteValue >= unlockThresholdPx && newOffset.absoluteValue < unlockThresholdPx && currentOffset.absoluteValue > 0) {
                                    haptics.perform(HapticPattern.Tick)
                                }
                            }
                        }
                    )
                }
        ) {
            content(shape)
        }
    }
}

@Composable
private fun PhysicsSwipeActionButton(
    onClick: () -> Unit,
    containerColor: Color,
    alpha: Float,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.85f else 1f, spring(0.6f, 300f), label = "scale")
    val pressAlpha by animateFloatAsState(if (isPressed) 0.7f else 1f, spring(0.6f, 300f), label = "alpha")

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        tonalElevation = 4.dp,
        interactionSource = interactionSource,
        modifier = Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha * pressAlpha }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
    }
}
