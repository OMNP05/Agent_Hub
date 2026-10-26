package dev.agenthub.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.agenthub.app.R
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
        // Tab bodies are placeholders (empty states) until Phases 5 to 9 fill them in.
        composable<HomeRoute> {
            EmptyState(
                icon = Icons.Outlined.SmartToy,
                title = stringResource(R.string.home_empty_title),
                message = stringResource(R.string.home_empty_message),
            )
        }
        composable<ActivityRoute> {
            EmptyState(
                icon = Icons.Outlined.Timeline,
                title = stringResource(R.string.activity_empty_title),
                message = stringResource(R.string.activity_empty_message),
            )
        }
        composable<ConnectorsRoute> {
            EmptyState(
                icon = Icons.Outlined.Cable,
                title = stringResource(R.string.connectors_empty_title),
                message = stringResource(R.string.connectors_empty_message),
            )
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
