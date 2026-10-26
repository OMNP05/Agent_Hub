package dev.agenthub.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun tabsAreExactlyHomeActivityConnectorsSettingsInOrder() {
        assertEquals(
            listOf("Home", "Activity", "Connectors", "Settings"),
            TopLevelDestination.entries.map { it.name },
        )
    }

    @Test
    fun everyTabHasADistinctRoute() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
