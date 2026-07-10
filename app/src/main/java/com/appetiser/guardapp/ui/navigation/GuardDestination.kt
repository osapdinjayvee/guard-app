package com.appetiser.guardapp.ui.navigation

import androidx.annotation.DrawableRes
import com.appetiser.guardapp.R

/**
 * Top-level destinations. Scan is deliberately absent from [bottomBarDestinations]: it is the
 * center action, rendered as a circular FAB rather than a bar item, per PRD §5.
 *
 * Icons are Heroicons v2 outline (MIT), vendored as vector drawables in res/drawable.
 */
enum class GuardDestination(
    val route: String,
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    Home("home", "Home", R.drawable.ic_home),
    History("history", "History", R.drawable.ic_history),
    Scan("scan", "Scan QR", R.drawable.ic_scan),
    Reports("reports", "Reports", R.drawable.ic_reports),
    Settings("settings", "Settings", R.drawable.ic_settings),
}

/** The four bar items, split either side of the center Scan action. */
val bottomBarDestinations: List<GuardDestination> = listOf(
    GuardDestination.Home,
    GuardDestination.History,
    GuardDestination.Reports,
    GuardDestination.Settings,
)
