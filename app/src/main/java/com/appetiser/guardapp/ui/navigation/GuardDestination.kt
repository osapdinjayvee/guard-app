package com.appetiser.guardapp.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations. Scan is deliberately absent from [bottomBarDestinations]: it is the
 * center action, rendered as a FAB rather than a bar item, per PRD §5.
 */
enum class GuardDestination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    History("history", "History", Icons.Default.History),
    Scan("scan", "Scan QR", Icons.Default.QrCodeScanner),
    Reports("reports", "Reports", Icons.Default.Assessment),
    Settings("settings", "Settings", Icons.Default.Settings),
}

/** The four bar items, split either side of the center Scan action. */
val bottomBarDestinations: List<GuardDestination> = listOf(
    GuardDestination.Home,
    GuardDestination.History,
    GuardDestination.Reports,
    GuardDestination.Settings,
)
