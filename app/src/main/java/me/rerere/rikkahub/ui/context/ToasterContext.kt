package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.ui.components.ui.AppToasterState

val LocalToaster = staticCompositionLocalOf<AppToasterState> { error("Not provided") }