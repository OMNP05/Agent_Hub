package dev.agenthub.app.shell

import dev.agenthub.app.push.FakeDeviceRepository
import dev.agenthub.app.push.NotificationNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun registersTheDeviceForPushOnStart() {
        val devices = FakeDeviceRepository()
        ShellViewModel(NotificationNavigation(), devices)
        assertEquals(1, devices.registerCalls)
    }

    @Test
    fun startsWithNoActiveAgentAndTracksSelection() {
        val vm = ShellViewModel(NotificationNavigation(), FakeDeviceRepository())
        assertNull(vm.uiState.value.activeAgent)

        val agent = ActiveAgent("a1", "Work Gmail")
        vm.setActiveAgent(agent)
        assertEquals(agent, vm.uiState.value.activeAgent)

        vm.setActiveAgent(null)
        assertNull(vm.uiState.value.activeAgent)
    }
}
