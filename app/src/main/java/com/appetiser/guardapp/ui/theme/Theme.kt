package com.appetiser.guardapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SeaGreen,
    onPrimary = Surface,
    primaryContainer = SeaGreenContainer,
    onPrimaryContainer = OnSeaGreenContainer,
    secondary = SeaGreenDark,
    onSecondary = Surface,
    secondaryContainer = SeaGreenContainer,
    onSecondaryContainer = OnSeaGreenContainer,
    tertiary = SeaGreenDeep,
    onTertiary = Surface,
    background = Cloud,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    // Cards sit on the cool ground as pure white; the ground itself is the "container".
    surfaceVariant = Cloud,
    onSurfaceVariant = InkMuted,
    surfaceContainer = Surface,
    surfaceContainerLow = Surface,
    surfaceContainerHigh = Cloud,
    outline = Outline,
    outlineVariant = Outline,
    error = SyncFailed,
    onError = Surface,
)

private val DarkColors = darkColorScheme(
    primary = SeaGreenOnDark,
    onPrimary = InkDark,
    primaryContainer = SeaGreenDeep,
    onPrimaryContainer = SeaGreenContainer,
    secondary = SeaGreenOnDark,
    onSecondary = InkDark,
    background = InkDark,
    onBackground = Cloud,
    surface = SurfaceDark,
    onSurface = Cloud,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = InkMuted,
    surfaceContainer = SurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = SyncFailed,
    onError = Surface,
)

/**
 * Dynamic colour is deliberately unsupported. Guards work outdoors in bright light and the
 * sea green accent carries meaning (it marks the primary attendance action), so it must not be
 * replaced by the device wallpaper's palette.
 */
@Composable
fun GuardAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = GuardShapes,
        content = content,
    )
}
