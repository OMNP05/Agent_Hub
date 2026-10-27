package dev.agenthub.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.agenthub.app.approvals.ui.ActivityScreen
import dev.agenthub.app.chat.ui.ChatScreen
import dev.agenthub.app.R
import dev.agenthub.app.agents.ui.connectors.ConnectorsScreen
import dev.agenthub.app.agents.ui.detail.AgentDetailScreen
import dev.agenthub.app.agents.ui.home.HomeScreen
import dev.agenthub.app.agents.ui.newagent.NewAgentScreen
import dev.agenthub.app.agents.ui.setup.AgentSetupScreen
import dev.agenthub.app.core.ui.components.EmptyState

private const val TransitionMillis = 220

private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(TransitionMillis)) + slideInVertically(tween(TransitionMillis)) { it / 24 }
}
private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(TransitionMillis / 2))
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier.padding(contentPadding),
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = enter,
        popExitTransition = exit,
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenAgent = { navController.navigate(ChatRoute(it)) },
                onNewAgent = { navController.navigate(NewAgentRoute) },
                onPickTemplate = { navController.navigate(AgentSetupRoute(it)) },
            )
        }
        composable<NewAgentRoute> {
            NewAgentScreen(
                onBack = { navController.popBackStack() },
                onPick = { navController.navigate(AgentSetupRoute(it)) },
            )
        }
        composable<AgentSetupRoute> {
            AgentSetupScreen(
                onBack = { navController.popBackStack() },
                // Replace the setup screens with the new agent so Back returns to Home, not to a finished form.
                onDone = { agentId ->
                    navController.navigate(AgentDetailRoute(agentId)) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }
        composable<AgentDetailRoute> { entry ->
            val agentId = entry.toRoute<AgentDetailRoute>().agentId
            AgentDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { navController.navigate(ChatRoute(agentId)) },
            )
        }
        composable<ChatRoute> { entry ->
            val agentId = entry.toRoute<ChatRoute>().agentId
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenAgent = { navController.navigate(AgentDetailRoute(agentId)) },
            )
        }
        composable<ActivityRoute> { entry ->
            ActivityScreen(highlightApprovalId = entry.toRoute<ActivityRoute>().highlightApprovalId)
        }
        composable<ConnectorsRoute> {
            ConnectorsScreen(onOpenAgent = { navController.navigate(AgentDetailRoute(it)) })
        }
        composable<SettingsRoute> {
            EmptyState(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.settings_title),
                message = stringResource(R.string.settings_empty_message),
            )
        }
    }
}
