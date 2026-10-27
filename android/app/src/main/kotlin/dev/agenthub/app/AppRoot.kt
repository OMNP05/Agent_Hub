package dev.agenthub.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import dev.agenthub.app.navigation.ActivityRoute
import dev.agenthub.app.navigation.AppNavHost
import dev.agenthub.app.navigation.ChatRoute
import dev.agenthub.app.navigation.TopLevelDestination
import dev.agenthub.app.shell.MiniBar
import dev.agenthub.app.shell.ShellViewModel

@Composable
fun AppRoot(shellViewModel: ShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val shell by shellViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(shellViewModel) {
        shellViewModel.openApprovals.collect { approvalId ->
            navController.navigate(ActivityRoute(highlightApprovalId = approvalId)) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                shell.activeAgent?.let { agent ->
                    // Chat and voice entry points are wired in Phases 6 and 8.
                    MiniBar(
                        agent = agent,
                        onOpen = { navController.navigate(ChatRoute(agent.id)) { launchSingleTop = true } },
                        // The mic opens the chat already listening; the transcript still needs a tap on Send.
                        onMic = { navController.navigate(ChatRoute(agent.id, listen = true)) { launchSingleTop = true } },
                    )
                }
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AppNavHost(navController = navController, contentPadding = padding)
    }
}
