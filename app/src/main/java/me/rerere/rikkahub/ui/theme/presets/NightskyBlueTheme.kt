package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val NightskyBlueThemePreset by lazy {
    PresetTheme(
        id = "nightsky_blue",
        name = {
            Text(stringResource(id = R.string.theme_name_nightsky_blue))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// Nightsky Blue Theme Colors
// Toned down, pastel-ish blue palette

// Light Theme
private val primaryLight = Color(0xFF4A6595) // Muted Blue
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD6E0FF)
private val onPrimaryContainerLight = Color(0xFF001E4B)
private val secondaryLight = Color(0xFF586071)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFDCE4F8)
private val onSecondaryContainerLight = Color(0xFF151C2B)
private val tertiaryLight = Color(0xFF725573)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFDD7FA)
private val onTertiaryContainerLight = Color(0xFF2A132D)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF410002)
private val backgroundLight = Color(0xFFF9F9FF)
private val onBackgroundLight = Color(0xFF1A1C20)
private val surfaceLight = Color(0xFFF9F9FF)
private val onSurfaceLight = Color(0xFF1A1C20)
private val surfaceVariantLight = Color(0xFFE1E2EC)
private val onSurfaceVariantLight = Color(0xFF44474F)
private val outlineLight = Color(0xFF757780)
private val outlineVariantLight = Color(0xFFC5C6D0)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2F3036)
private val inverseOnSurfaceLight = Color(0xFFF0F0F7)
private val inversePrimaryLight = Color(0xFFA8C7FF)
private val surfaceDimLight = Color(0xFFD9D9E0)
private val surfaceBrightLight = Color(0xFFF9F9FF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF3F3FA)
private val surfaceContainerLight = Color(0xFFEDEDF4)
private val surfaceContainerHighLight = Color(0xFFE7E8EE)
private val surfaceContainerHighestLight = Color(0xFFE2E2E9)

// Dark Theme
private val primaryDark = Color(0xFFA8C7FF) // Pastel Blue
private val onPrimaryDark = Color(0xFF13305E)
private val primaryContainerDark = Color(0xFF314876)
private val onPrimaryContainerDark = Color(0xFFD6E0FF)
private val secondaryDark = Color(0xFFBFC6DC)
private val onSecondaryDark = Color(0xFF2A3141)
private val secondaryContainerDark = Color(0xFF404858)
private val onSecondaryContainerDark = Color(0xFFDCE4F8)
private val tertiaryDark = Color(0xFFE0BBDE)
private val onTertiaryDark = Color(0xFF412843)
private val tertiaryContainerDark = Color(0xFF593E5A)
private val onTertiaryContainerDark = Color(0xFFFDD7FA)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF111318)
private val onBackgroundDark = Color(0xFFE2E2E9)
private val surfaceDark = Color(0xFF111318)
private val onSurfaceDark = Color(0xFFE2E2E9)
private val surfaceVariantDark = Color(0xFF44474F)
private val onSurfaceVariantDark = Color(0xFFC5C6D0)
private val outlineDark = Color(0xFF8F9099)
private val outlineVariantDark = Color(0xFF44474F)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE2E2E9)
private val inverseOnSurfaceDark = Color(0xFF2F3036)
private val inversePrimaryDark = Color(0xFF4A6595)
private val surfaceDimDark = Color(0xFF111318)
private val surfaceBrightDark = Color(0xFF37393E)
private val surfaceContainerLowestDark = Color(0xFF0C0E13)
private val surfaceContainerLowDark = Color(0xFF1A1C20)
private val surfaceContainerDark = Color(0xFF1E2025)
private val surfaceContainerHighDark = Color(0xFF282A2F)
private val surfaceContainerHighestDark = Color(0xFF33353A)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
