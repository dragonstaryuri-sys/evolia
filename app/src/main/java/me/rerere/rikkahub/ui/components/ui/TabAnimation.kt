package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Animated tab component matching PixelPlay's library page style.
 * Features:
 * - Scale animation (1f → 1.15f → 1f) on selection
 * - Neighbor offset animation creating "push" effect
 * - Color transition with 200ms duration
 * - Haptic feedback on tap
 */
@Composable
fun TabAnimation(
    modifier: Modifier = Modifier,
    index: Int,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    onSelectedColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedColor: Color = MaterialTheme.colorScheme.surface,
    onUnselectedColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    title: String,
    selectedIndex: Int,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    val isSelected = index == selectedIndex
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }

    val animationSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 400f)

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) selectedColor else unselectedColor,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "Tab Background Color"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) onSelectedColor else onUnselectedColor,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "Tab Content Color"
    )

    // Handles the "pop" animation for the selected tab
    LaunchedEffect(isSelected) {
        if (isSelected) {
            launch {
                scale.animateTo(1.15f, animationSpec = animationSpec)
                scale.animateTo(1f, animationSpec = animationSpec)
            }
        } else {
            // Instantly reset scale for non-selected tabs
            scale.snapTo(1f)
        }
    }

    // Handles the offset for neighbor tabs when selection changes
    LaunchedEffect(selectedIndex) {
        if (!isSelected) {
            val distance = index - selectedIndex
            if (abs(distance) == 1) { // Only affect direct neighbors
                val direction = if (distance > 0) 1 else -1
                val offsetValue = 12f * direction
                launch {
                    offsetX.animateTo(offsetValue, animationSpec = animationSpec)
                    offsetX.animateTo(0f, animationSpec = animationSpec)
                }
            } else {
                offsetX.snapTo(0f)
            }
        } else {
            offsetX.snapTo(0f)
        }
    }

    Tab(
        modifier = modifier
            .padding(
                horizontal = 8.dp,
                vertical = 12.dp
            )
            .graphicsLayer {
                scaleX = scale.value
                translationX = offsetX.value
            }
            .clip(CircleShape)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(50)
            ),
        selected = isSelected,
        text = content,
        onClick = {
            haptics.perform(HapticPattern.Tick)
            onClick()
        },
        selectedContentColor = contentColor,
        unselectedContentColor = contentColor
    )
}
