package dev.agenthub.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellViewModelTest {
    @Test
    fun startsWithNoActiveAgentAndTracksSelection() {
        val vm = ShellViewModel()
        assertNull(vm.uiState.value.activeAgent)

        val agent = ActiveAgent("a1", "Work Gmail")
        vm.setActiveAgent(agent)
        assertEquals(agent, vm.uiState.value.activeAgent)

        vm.setActiveAgent(null)
        assertNull(vm.uiState.value.activeAgent)
    }
}
