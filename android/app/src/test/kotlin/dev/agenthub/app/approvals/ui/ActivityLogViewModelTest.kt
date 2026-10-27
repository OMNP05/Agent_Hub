package dev.agenthub.app.approvals.ui

import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.testAgent
import dev.agenthub.app.approvals.FakeApprovalRepository
import dev.agenthub.app.approvals.logEntry
import dev.agenthub.app.core.error.AppResult
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
class ActivityLogViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun page(prefix: String, n: Int) = AppResult.Success((1..n).map { logEntry("$prefix$it", "2026-10-26T10:%02d:00Z".format(60 - it)) })

    @Test
    fun noAgentsMeansNothingSelected() {
        val v = ActivityLogViewModel(FakeApprovalRepository(), FakeAgentRepository())
        assertNull(v.uiState.value.selectedAgentId)
        assertFalse(v.uiState.value.showEmpty)
    }

    @Test
    fun selectsTheFirstAgentAndLoadsItsLog() {
        val repo = FakeApprovalRepository().apply { logPages += page("e", 3) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"), testAgent("a2"))))
        assertEquals("a1", v.uiState.value.selectedAgentId)
        assertEquals(3, v.uiState.value.entries.size)
        assertEquals("a1", repo.logRequests.single().first)
    }

    @Test
    fun emptyLogShowsTheEmptyState() {
        val v = ActivityLogViewModel(FakeApprovalRepository(), FakeAgentRepository(listOf(testAgent("a1"))))
        assertTrue(v.uiState.value.showEmpty)
    }

    @Test
    fun switchingAgentReplacesTheEntries() {
        val repo = FakeApprovalRepository().apply { logPages += page("a", 2); logPages += page("b", 4) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"), testAgent("a2"))))
        v.select("a2")
        assertEquals("a2", v.uiState.value.selectedAgentId)
        assertEquals(4, v.uiState.value.entries.size)
        assertTrue(v.uiState.value.entries.all { it.id.startsWith("b") })
    }

    @Test
    fun aFullPageEnablesLoadMoreUsingTheLastTimestampAsCursor() {
        val repo = FakeApprovalRepository().apply { logPages += page("p1-", 30); logPages += page("p2-", 5) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        assertFalse(v.uiState.value.endReached)

        v.loadMore()

        assertEquals(35, v.uiState.value.entries.size)
        assertTrue("short page means the end", v.uiState.value.endReached)
        assertEquals(v.uiState.value.entries[29].createdAt.toString(), repo.logRequests[1].second)
    }

    @Test
    fun aShortFirstPageNeverOffersLoadMore() {
        val repo = FakeApprovalRepository().apply { logPages += page("e", 3) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        assertTrue(v.uiState.value.endReached)
        v.loadMore()
        assertEquals(1, repo.logRequests.size)
    }

    @Test
    fun duplicateEntriesAcrossPagesAreMergedOnce() {
        val repo = FakeApprovalRepository().apply { logPages += page("x", 30); logPages += AppResult.Success(listOf(logEntry("x30"), logEntry("new"))) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        v.loadMore()
        assertEquals(31, v.uiState.value.entries.size)
    }

    @Test
    fun loadFailureShowsAnErrorAndRetryRecovers() {
        val repo = FakeApprovalRepository().apply { logPages += AppResult.Failure(DataError.Network); logPages += page("e", 2) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        assertEquals(DataError.Network, v.uiState.value.error)

        v.retry()

        assertNull(v.uiState.value.error)
        assertEquals(2, v.uiState.value.entries.size)
    }

    @Test
    fun failedLoadMoreKeepsTheEntriesAlreadyShown() {
        val repo = FakeApprovalRepository().apply { logPages += page("e", 30); logPages += AppResult.Failure(DataError.Network) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        v.loadMore()
        assertEquals(30, v.uiState.value.entries.size)
        assertNull(v.uiState.value.error)
    }

    @Test
    fun tappingAnEntryExpandsItAndTappingAgainCollapses() {
        val repo = FakeApprovalRepository().apply { logPages += page("e", 2) }
        val v = ActivityLogViewModel(repo, FakeAgentRepository(listOf(testAgent("a1"))))
        v.toggleExpanded("e1")
        assertEquals("e1", v.uiState.value.expandedId)
        v.toggleExpanded("e1")
        assertNull(v.uiState.value.expandedId)
    }
}
