package com.appetiser.guardapp.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.appetiser.guardapp.ui.theme.GuardAppTheme

@Composable
fun GuardAppScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            BottomAppBar(
                actions = {
                    // Two items, the FAB, then two items — Scan sits in the middle.
                    bottomBarDestinations.take(2).forEach { destination ->
                        BarItem(destination, currentDestination?.isOn(destination) == true) {
                            navController.navigateToTopLevel(destination)
                        }
                    }
                    bottomBarDestinations.drop(2).forEach { destination ->
                        BarItem(destination, currentDestination?.isOn(destination) == true) {
                            navController.navigateToTopLevel(destination)
                        }
                    }
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { navController.navigateToTopLevel(GuardDestination.Scan) },
                        modifier = Modifier.semantics {
                            contentDescription = GuardDestination.Scan.label
                        },
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = GuardDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            GuardDestination.entries.forEach { destination ->
                composable(destination.route) { PlaceholderRoute(destination) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(
    destination: GuardDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(destination.icon, contentDescription = null) },
        label = { Text(destination.label) },
        modifier = Modifier.semantics { contentDescription = destination.label },
    )
}

private fun androidx.navigation.NavDestination.isOn(destination: GuardDestination): Boolean =
    hierarchy.any { it.route == destination.route }

/**
 * Single-top navigation that pops back to the start destination, so the back stack cannot grow
 * unbounded as a guard taps between tabs.
 */
private fun NavHostController.navigateToTopLevel(destination: GuardDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun PlaceholderRoute(destination: GuardDestination) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(destination.label, style = MaterialTheme.typography.headlineMedium)
        Text("Not yet implemented", style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun GuardAppScaffoldPreview() {
    GuardAppTheme { GuardAppScaffold() }
}
