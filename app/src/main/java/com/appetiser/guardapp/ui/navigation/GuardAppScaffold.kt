package com.appetiser.guardapp.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.appetiser.guardapp.ui.theme.GuardAppTheme

/** Material 3 NavigationBar's own height. */
private val BarHeight = 80.dp

/** How far the Scan FAB rises above the top edge of the bar. */
private val FabLift = 28.dp

private val FabSize = 72.dp

@Composable
fun GuardAppScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            // BottomAppBar always docks its FAB at the end, so the center action is built by
            // hand: two tabs, the FAB, two tabs. NavigationBar's content is a RowScope, which
            // is what lets the FAB take a weighted slot in the middle.
            // The Scan FAB is raised above the bar. It is NOT drawn with a negative offset:
            // a child rendered outside its parent's bounds still paints but stops receiving
            // touches, so the slot is sized to contain the raised button instead.
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
                    // Reserve the middle slot for the FAB.
                    Spacer(Modifier.weight(1f))
                    bottomBarDestinations.drop(2).forEach { destination ->
                        BarItem(destination, currentDestination?.isOn(destination) == true) {
                            navController.navigateToTopLevel(destination)
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { navController.navigateToTopLevel(GuardDestination.Scan) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(FabSize)
                        .semantics { contentDescription = GuardDestination.Scan.label },
                ) {
                    Icon(
                        painter = painterResource(GuardDestination.Scan.icon),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
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
        icon = {
            Icon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
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

/**
 * Placeholder content, shaped as the white card on a cool ground that the rest of the app
 * will use. Real screens replace these in Phase 5.
 */
@Composable
private fun PlaceholderRoute(destination: GuardDestination) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    destination.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Not yet implemented",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GuardAppScaffoldPreview() {
    GuardAppTheme { GuardAppScaffold() }
}
