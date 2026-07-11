package com.minsu.guardapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = SurfaceLight,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandStrong,
    secondary = Accent,
    onSecondary = TextHeading,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = TextHeading,
    tertiary = BrandStrong,
    onTertiary = SurfaceLight,
    background = Background,
    onBackground = TextHeading,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = TextMuted,
    surfaceContainer = SurfaceLight,
    surfaceContainerLow = SurfaceAlt,
    surfaceContainerHigh = Background,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = SyncFailed,
    onError = SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = BackgroundDark,
    primaryContainer = BrandSoftDark,
    onPrimaryContainer = BrandStrongDark,
    secondary = Accent,
    onSecondary = BackgroundDark,
    secondaryContainer = BrandSoftDark,
    onSecondaryContainer = Accent,
    tertiary = BrandStrongDark,
    onTertiary = BackgroundDark,
    background = BackgroundDark,
    onBackground = TextHeadingDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceAltDark,
    onSurfaceVariant = TextMutedDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = SurfaceAltDark,
    surfaceContainerHigh = SurfaceDark,
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = SyncFailed,
    onError = SurfaceLight,
)

/**
 * Dynamic colour is deliberately unsupported. The brand green carries meaning — it marks the
 * attendance action — and guards work outdoors where a predictable, high-contrast palette
 * matters more than matching the device wallpaper.
 */
@Composable
fun GuardAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GuardTypography,
        shapes = GuardShapes,
        content = content,
    )
}
