package dev.agenthub.app.approvals.ui

import dev.agenthub.app.approvals.FakeApprovalRepository
import dev.agenthub.app.approvals.approval
import dev.agenthub.app.approvals.data.Decision
import dev.agenthub.app.approvals.expired
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.ApprovalStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeApprovalRepository) = ApprovalsViewModel(repo)

    @Test
    fun showsPendingApprovalsOnly() {
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1"), approval("p2", status = ApprovalStatus.Approved)))
        }
        assertEquals(listOf("p1"), vm(repo).uiState.value.pending.map { it.id })
    }

    @Test
    fun emptyListShowsTheEmptyState() {
        val state = vm(FakeApprovalRepository()).uiState.value
        assertTrue(state.showEmpty)
        assertFalse(state.isLoading)
    }

    @Test
    fun loadFailureWithNothingShownIsABlockingError() {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Failure(DataError.Network) }
        val state = vm(repo).uiState.value
        assertTrue(state.showBlockingError)
        assertEquals(DataError.Network, state.error)
    }

    @Test
    fun refreshReloadsTheList() {
        val repo = FakeApprovalRepository()
        val v = vm(repo)
        repo.pendingResult = AppResult.Success(listOf(approval("new")))
        v.refresh()
        assertEquals(listOf("new"), v.uiState.value.pending.map { it.id })
    }

    @Test
    fun rejectSendsRejectAndMarksItDecided() = runTest {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Success(listOf(approval("p1"))) }
        val v = vm(repo)

        v.onRejectTap(v.uiState.value.pending.single())

        assertEquals(listOf("p1" to Decision.Reject), repo.decisions)
        assertTrue(v.uiState.value.pending.isEmpty())
        assertEquals(ApprovalsEffect.Decided(Decision.Reject), v.effects.first())
    }

    @Test
    fun writeApprovalGoesStraightThrough() = runTest {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Success(listOf(approval("p1", tool = "github.update_profile"))) }
        val v = vm(repo)

        v.onApproveTap(v.uiState.value.pending.single())

        assertEquals(listOf("p1" to Decision.Approve), repo.decisions)
        assertNull(v.uiState.value.confirming)
    }

    @Test
    fun dangerousApprovalNeedsASecondConfirmationBeforeAnythingIsSent() = runTest {
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1", tool = "gmail.send_email", args = mapOf("subject" to "Hi"))))
        }
        val v = vm(repo)

        v.onApproveTap(v.uiState.value.pending.single())
        assertNotNull(v.uiState.value.confirming)
        assertTrue("nothing may be sent before the confirmation", repo.decisions.isEmpty())

        v.onConfirmApprove()
        assertEquals(listOf("p1" to Decision.Approve), repo.decisions)
        assertNull(v.uiState.value.confirming)
    }

    @Test
    fun dismissingTheConfirmationSendsNothing() {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Success(listOf(approval("p1", tool = "calendar.delete_event"))) }
        val v = vm(repo)
        v.onApproveTap(v.uiState.value.pending.single())
        v.onConfirmDismiss()
        assertTrue(repo.decisions.isEmpty())
        assertNull(v.uiState.value.confirming)
    }

    @Test
    fun unknownToolsAreTreatedAsDangerous() {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Success(listOf(approval("p1", tool = "mystery.tool"))) }
        val v = vm(repo)
        v.onApproveTap(v.uiState.value.pending.single())
        assertNotNull(v.uiState.value.confirming)
        assertTrue(repo.decisions.isEmpty())
    }

    @Test
    fun doubleTapSendsExactlyOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1")))
            this.gate = gate
        }
        val v = vm(repo)
        val item = v.uiState.value.pending.single()

        v.onApproveTap(item)
        assertTrue("buttons stay disabled while in flight", "p1" in v.uiState.value.inFlight)
        v.onApproveTap(item)
        v.onRejectTap(item)
        gate.complete(Unit)

        assertEquals(1, repo.decisions.size)
        assertTrue(v.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun anAlreadyDecidedApprovalIsDroppedAndReported() = runTest {
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1")))
            decideResult = expired
        }
        val v = vm(repo)

        v.onApproveTap(v.uiState.value.pending.single())

        assertTrue(v.uiState.value.approvals.isEmpty())
        assertEquals(ApprovalsEffect.AlreadyHandled, v.effects.first())
        assertNull("expiry is not a generic error", v.uiState.value.error)
    }

    @Test
    fun aNetworkFailureKeepsTheApprovalSoItCanBeRetried() = runTest {
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1")))
            decideResult = AppResult.Failure(DataError.Network)
        }
        val v = vm(repo)

        v.onRejectTap(v.uiState.value.pending.single())

        assertEquals(1, v.uiState.value.pending.size)
        assertEquals(DataError.Network, v.uiState.value.error)
        assertTrue(v.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun alreadyDecidedItemsCannotBeTappedAgain() {
        val repo = FakeApprovalRepository().apply {
            pendingResult = AppResult.Success(listOf(approval("p1", status = ApprovalStatus.Approved)))
        }
        val v = vm(repo)
        v.onApproveTap(v.uiState.value.approvals.single())
        v.onRejectTap(v.uiState.value.approvals.single())
        assertTrue(repo.decisions.isEmpty())
    }

    @Test
    fun highlightingAnApprovalNotInTheListRefreshesIt() {
        val repo = FakeApprovalRepository()
        val v = vm(repo)
        val before = repo.pendingCalls
        v.highlight("from-notification")
        assertEquals("from-notification", v.uiState.value.highlightId)
        assertEquals(before + 1, repo.pendingCalls)
    }

    @Test
    fun highlightingAnApprovalAlreadyListedDoesNotRefresh() {
        val repo = FakeApprovalRepository().apply { pendingResult = AppResult.Success(listOf(approval("p1"))) }
        val v = vm(repo)
        val before = repo.pendingCalls
        v.highlight("p1")
        assertEquals(before, repo.pendingCalls)
    }
}
