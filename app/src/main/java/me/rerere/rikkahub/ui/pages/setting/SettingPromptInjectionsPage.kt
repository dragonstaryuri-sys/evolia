package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

/**
 * This page redirects to Modes page.
 * Navigation now goes directly to Modes from Settings.
 */
@Composable
fun SettingPromptInjectionsPage() {
    val navController = LocalNavController.current
    
    LaunchedEffect(Unit) {
        navController.navigate(Screen.SettingModes()) {
            popUpTo(Screen.SettingPromptInjections) { inclusive = true }
        }
    }
}
