package com.minsu.guardapp.ui.navigation

import androidx.annotation.DrawableRes
import com.minsu.guardapp.R

/**
 * Bottom-bar destinations, styled after the MinSUverse portal.
 *
 * [ScanQr] is the raised centre action and is not a bar item — it is the primary attendance
 * action, and every other screen exists to serve it.
 */
enum class GuardDestination(
    val route: String,
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    Home("home", "Home", R.drawable.ic_nav_home),
    Schedule("schedule", "Schedule", R.drawable.ic_calendar),
    History("history", "History", R.drawable.ic_nav_history),
    ScanQr("scan", "Scan QR", R.drawable.ic_nav_scan),
    Reports("reports", "Reports", R.drawable.ic_nav_reports),
    Account("account", "Account", R.drawable.ic_nav_account),
}

/**
 * The four bar items, split either side of the centre action.
 *
 * [GuardDestination.History] is not among them. A guard looks up what they already did far less
 * often than they check what they are on next, and the roster is what the scanner enforces — so
 * Schedule earns the standing slot. History is still a screen; it is reached from Home's DTR tile.
 */
val bottomBarDestinations: List<GuardDestination> = listOf(
    GuardDestination.Home,
    GuardDestination.Schedule,
    GuardDestination.Reports,
    GuardDestination.Account,
)
