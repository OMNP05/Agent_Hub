package dev.agenthub.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import dev.agenthub.app.R
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object ActivityRoute
@Serializable data object ConnectorsRoute
@Serializable data object SettingsRoute

@Serializable data object NewAgentRoute
@Serializable data class AgentSetupRoute(val templateId: String)
@Serializable data class AgentDetailRoute(val agentId: String)

/** The four bottom-bar tabs, in display order (blueprint 3.6). */
enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(HomeRoute, R.string.tab_home, Icons.Outlined.Home),
    Activity(ActivityRoute, R.string.tab_activity, Icons.Outlined.Timeline),
    Connectors(ConnectorsRoute, R.string.tab_connectors, Icons.Outlined.Cable),
    Settings(SettingsRoute, R.string.tab_settings, Icons.Outlined.Settings),
}
