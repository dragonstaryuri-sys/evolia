package me.rerere.rikkahub.ui.context

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope> {
    error("No SharedTransitionScope provided")
}

// Outer NavHost scope (RouteActivity level)
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope> {
    error("No AnimatedVisibilityScope provided")
}

// Inner NavHost scope (nested navigation like AssistantDetail sub-pages)
val LocalInnerAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> {
    null // Optional - may not be in an inner NavHost
}
