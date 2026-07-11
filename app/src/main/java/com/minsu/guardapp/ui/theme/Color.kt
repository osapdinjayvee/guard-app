package com.minsu.guardapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens copied verbatim from the MinSUverse portal (`src/assets/main.css`), not
 * sampled from screenshots. Names mirror the CSS custom properties so the two stay in step.
 */

// Light
val Brand = Color(0xFF005825)
val BrandStrong = Color(0xFF00461D)
val BrandSoft = Color(0xFFE6F3EB)
val Accent = Color(0xFFFFB21A)
val AccentSoft = Color(0xFFFFF3D6)
val TextPrimary = Color(0xFF1F2A24)
val TextHeading = Color(0xFF06180E)
val TextMuted = Color(0xFF5A6B62)
val Background = Color(0xFFF4F7F3)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceAlt = Color(0xFFF8FBF7)
val BorderLight = Color(0xFFE3EAE3)

// Dark
val BrandDark = Color(0xFF4DD182)
val BrandStrongDark = Color(0xFF86E5A8)
/** `rgba(0, 152, 79, 0.16)` — 0.16 alpha is 0x29. */
val BrandSoftDark = Color(0x2900984F)
val TextPrimaryDark = Color(0xFFCBD5D0)
val TextHeadingDark = Color(0xFFF1F5F2)
val TextMutedDark = Color(0xFF8AA097)
val BackgroundDark = Color(0xFF0A1410)
val SurfaceDark = Color(0xFF11201A)
val SurfaceAltDark = Color(0xFF0D1A14)
val BorderDark = Color(0xFF1D3328)

// Status. Saturated on purpose: a guard reads these at a glance in daylight, so they are not
// harmonised toward the brand green.
val SyncPending = Accent
val SyncFailed = Color(0xFFD1495B)
val SyncSynced = Brand
