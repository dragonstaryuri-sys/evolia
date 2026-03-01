package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import me.rerere.rikkahub.ui.context.LocalAnimatedVisibilityScope
import me.rerere.rikkahub.ui.context.LocalInnerAnimatedVisibilityScope
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope

// Smooth spring-based bounds transform for seamless size and position animations
private val HeroBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}

/**
 * Hero animation for outer NavHost transitions (e.g., List → Detail page)
 * Animates both position and size smoothly between screens.
 */
@Composable
fun Modifier.heroAnimation(
    key: Any,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    return with(sharedTransitionScope) {
        this@heroAnimation.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = HeroBoundsTransform
        )
    }
}

/**
 * Hero animation for inner NavHost transitions (e.g., Detail Home → Detail SubPage)
 * Returns unmodified if inner scope is not available.
 */
@Composable
fun Modifier.heroAnimationInner(
    key: Any,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val innerScope = LocalInnerAnimatedVisibilityScope.current ?: return this
    return with(sharedTransitionScope) {
        this@heroAnimationInner.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = innerScope,
            boundsTransform = HeroBoundsTransform
        )
    }
}
