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
    History("history", "History", R.drawable.ic_nav_history),
    ScanQr("scan", "Scan QR", R.drawable.ic_nav_scan),
    Reports("reports", "Reports", R.drawable.ic_nav_reports),
    Account("account", "Account", R.drawable.ic_nav_account),
}

/** The four bar items, split either side of the centre action. */
val bottomBarDestinations: List<GuardDestination> = listOf(
    GuardDestination.Home,
    GuardDestination.History,
    GuardDestination.Reports,
    GuardDestination.Account,
)
