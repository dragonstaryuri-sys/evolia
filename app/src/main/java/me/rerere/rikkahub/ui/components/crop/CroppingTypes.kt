package me.rerere.rikkahub.ui.components.crop

import androidx.compose.animation.core.EaseInOutExpo
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/**
 * Enum representing the selected area of the crop box during drag operations.
 */
@Immutable
enum class SelectedCropArea {
    TopLeftCorner,
    TopRightCorner,
    BottomLeftCorner,
    BottomRightCorner,
    TopEdge,
    LeftEdge,
    BottomEdge,
    RightEdge,
    None,
    Whole
}

/**
 * Animation constants for the crop UI.
 */
object CropAnimationConstants {
    const val DURATION = 350
    
    fun <T> expressiveTween(durationMillis: Int = DURATION) = tween<T>(
        durationMillis = durationMillis,
        easing = EaseInOutExpo
    )
}
