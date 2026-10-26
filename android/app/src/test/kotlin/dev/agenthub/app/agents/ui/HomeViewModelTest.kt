package dev.agenthub.app.agents.ui

import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.networkFailure
import dev.agenthub.app.agents.testAgent
import dev.agenthub.app.agents.ui.home.HomeViewModel
import dev.agenthub.app.core.error.DataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyListAfterSuccessfulRefreshShowsEmptyState() {
        val vm = HomeViewModel(FakeAgentRepository())
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.showEmpty)
        assertFalse(state.showBlockingError)
    }

    @Test
    fun agentsFromCacheAreShown() {
        val vm = HomeViewModel(FakeAgentRepository(listOf(testAgent("a1"), testAgent("a2"))))
        assertEquals(listOf("a1", "a2"), vm.uiState.value.agents.map { it.id })
        assertFalse(vm.uiState.value.showEmpty)
    }

    @Test
    fun failedRefreshWithNothingCachedIsABlockingError() {
        val repo = FakeAgentRepository().apply { refreshResult = networkFailure }
        val state = HomeViewModel(repo).uiState.value
        assertEquals(DataError.Network, state.error)
        assertTrue(state.showBlockingError)
        assertFalse(state.showEmpty)
    }

    @Test
    fun failedRefreshWithCacheKeepsAgentsVisible() {
        val repo = FakeAgentRepository(listOf(testAgent("a1"))).apply { refreshResult = networkFailure }
        val state = HomeViewModel(repo).uiState.value
        assertEquals(1, state.agents.size)
        assertFalse(state.showBlockingError)
    }

    @Test
    fun retryRefreshesAgainAndClearsTheError() {
        val repo = FakeAgentRepository().apply { refreshResult = networkFailure }
        val vm = HomeViewModel(repo)
        assertEquals(DataError.Network, vm.uiState.value.error)

        repo.refreshResult = dev.agenthub.app.core.error.AppResult.Success(Unit)
        vm.refresh()

        assertNull(vm.uiState.value.error)
        assertEquals(2, repo.refreshCount)
    }

    @Test
    fun newAgentAppearsWhenCacheEmits() {
        val repo = FakeAgentRepository()
        val vm = HomeViewModel(repo)
        repo.agents.value = listOf(testAgent("a9"))
        assertEquals(listOf("a9"), vm.uiState.value.agents.map { it.id })
    }
}
