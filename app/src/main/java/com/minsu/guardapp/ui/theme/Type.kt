package com.minsu.guardapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.minsu.guardapp.R

/**
 * Plus Jakarta Sans, the portal's typeface (OFL — see licenses/plus-jakarta-sans-OFL.txt).
 *
 * It ships as a single variable font. `FontVariation` selects weights on API 26+; on API 24–25
 * every weight resolves to the file's default instance, so text renders in one weight rather
 * than falling back to a different typeface. That trade was taken over bundling four static
 * files, since ~all devices in service are API 26+.
 */
@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlusJakartaSans = FontFamily(
    jakarta(400),
    jakarta(500),
    jakarta(600),
    jakarta(700),
    jakarta(800),
)

/** Scale mirrors the portal's `--text-*` custom properties (1rem = 14sp there). */
val GuardTypography = Typography().run {
    val base = this
    Typography(
        displaySmall = base.displaySmall.jakarta(FontWeight.Bold),
        headlineLarge = base.headlineLarge.jakarta(FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.jakarta(FontWeight.Bold),
        headlineSmall = base.headlineSmall.jakarta(FontWeight.Bold),
        titleLarge = base.titleLarge.jakarta(FontWeight.Bold),
        titleMedium = base.titleMedium.jakarta(FontWeight.SemiBold),
        titleSmall = base.titleSmall.jakarta(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.jakarta(FontWeight.Normal),
        bodyMedium = base.bodyMedium.jakarta(FontWeight.Normal),
        bodySmall = base.bodySmall.jakarta(FontWeight.Normal),
        labelLarge = base.labelLarge.jakarta(FontWeight.SemiBold),
        labelMedium = base.labelMedium.jakarta(FontWeight.Medium),
        labelSmall = base.labelSmall.jakarta(FontWeight.Medium),
    )
}

private fun TextStyle.jakarta(weight: FontWeight) =
    copy(fontFamily = PlusJakartaSans, fontWeight = weight)

/** The portal's uppercase, letter-spaced pill label ("EMPLOYEE SELF-SERVICE PORTAL"). */
val PillLabel = TextStyle(
    fontFamily = PlusJakartaSans,
    fontWeight = FontWeight.Bold,
    fontSize = 12.sp,
    letterSpacing = 1.2.sp,
)
