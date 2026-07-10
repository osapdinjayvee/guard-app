package com.appetiser.guardapp.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.appetiser.guardapp.feature.account.AccountScreen
import com.appetiser.guardapp.feature.history.HistoryScreen
import com.appetiser.guardapp.feature.home.HomeRoute
import com.appetiser.guardapp.feature.reports.ReportsScreen
import com.appetiser.guardapp.feature.scan.ScanQrScreen

private val BarHeight = 76.dp

/** How far the centre action rises above the bar's top edge. */
private val FabLift = 26.dp
private val FabSize = 62.dp

@Composable
fun GuardAppScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // The centre action is raised above the bar. It is not drawn with a negative
            // offset: a child painted outside its parent's bounds still renders but stops
            // receiving touches, so the slot is sized to contain it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BarHeight + FabLift),
            ) {
                NavigationBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    bottomBarDestinations.take(2).forEach { destination ->
                        BarItem(destination, currentDestination?.isOn(destination) == true) {
                            navController.navigateToTopLevel(destination)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    bottomBarDestinations.drop(2).forEach { destination ->
                        BarItem(destination, currentDestination?.isOn(destination) == true) {
                            navController.navigateToTopLevel(destination)
                        }
                    }
                }

                CentreAction(
                    selected = currentDestination?.isOn(GuardDestination.ScanQr) == true,
                    onClick = { navController.navigateToTopLevel(GuardDestination.ScanQr) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = GuardDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(GuardDestination.Home.route) { HomeRoute() }
            composable(GuardDestination.History.route) { HistoryScreen() }
            composable(GuardDestination.ScanQr.route) { ScanQrScreen() }
            composable(GuardDestination.Reports.route) { ReportsScreen() }
            composable(GuardDestination.Account.route) { AccountScreen() }
        }
    }
}

@Composable
private fun CentreAction(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            modifier = Modifier
                .size(FabSize)
                .semantics { contentDescription = GuardDestination.ScanQr.label },
        ) {
            // Two-tone drawable: its own white and amber, so it must not be tinted.
            Icon(
                painter = painterResource(GuardDestination.ScanQr.icon),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            GuardDestination.ScanQr.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun RowScope.BarItem(
    destination: GuardDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        label = {
            Text(
                destination.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            // No pill behind the active tab; colour and weight alone mark selection.
            indicatorColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.semantics { contentDescription = destination.label },
    )
}

private fun androidx.navigation.NavDestination.isOn(destination: GuardDestination): Boolean =
    hierarchy.any { it.route == destination.route }

/** Single-top navigation so the back stack cannot grow as a guard taps between tabs. */
private fun NavHostController.navigateToTopLevel(destination: GuardDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
