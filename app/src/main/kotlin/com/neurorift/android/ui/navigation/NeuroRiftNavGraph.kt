package com.neurorift.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.neurorift.android.R
import com.neurorift.android.ui.screens.DashboardScreen
import com.neurorift.android.ui.screens.PlaceholderScreen
import com.neurorift.android.ui.screens.ProfilesScreen

/**
 * App navigation graph (issue #9). Bottom navigation with five destinations;
 * secondary routes (Connections/Logs/Rules) stay stable for the cockpit
 * issue (#13). State restoration comes from NavController automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuroRiftNavGraph(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val current = Destination.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            id = current?.labelRes ?: R.string.app_name,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(imageVector = destination.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            Destination.entries.forEach { destination ->
                composable(destination.route) {
                    when (destination) {
                        Destination.Dashboard -> DashboardScreen(onConnect = onConnect, onDisconnect = onDisconnect)
                        Destination.Profiles -> ProfilesScreen()
                        else -> PlaceholderScreen(
                            title = stringResource(destination.labelRes),
                            icon = destination.icon,
                            issueNumber = placeholderIssueOf(destination),
                        )
                    }
                }
            }
            composable(SecondaryRoute.CONNECTIONS) {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_connections),
                    icon = Destination.Dashboard.icon,
                    issueNumber = 13,
                )
            }
            composable(SecondaryRoute.LOGS) {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_logs),
                    icon = Destination.Dashboard.icon,
                    issueNumber = 13,
                )
            }
            composable(SecondaryRoute.RULES) {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_rules),
                    icon = Destination.Dashboard.icon,
                    issueNumber = 13,
                )
            }
        }
    }
}

/** Which issue fills which destination — keeps the placeholders honest. */
private fun placeholderIssueOf(destination: Destination): Int = when (destination) {
    Destination.Dashboard -> 13
    Destination.Proxies -> 12
    Destination.Profiles -> 11
    Destination.Test -> 15
    Destination.Settings -> 14
}
