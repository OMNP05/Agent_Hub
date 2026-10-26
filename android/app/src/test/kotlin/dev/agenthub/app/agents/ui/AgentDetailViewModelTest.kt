package dev.agenthub.app.agents.ui

import androidx.lifecycle.SavedStateHandle
import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.networkFailure
import dev.agenthub.app.agents.oauth.OAuthReturn
import dev.agenthub.app.agents.oauth.OAuthReturnBus
import dev.agenthub.app.agents.testAgent
import dev.agenthub.app.agents.ui.detail.AgentDetailEffect
import dev.agenthub.app.agents.ui.detail.AgentDetailViewModel
import dev.agenthub.app.agents.ui.detail.DetailMessage
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.ApprovalMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeAgentRepository, bus: OAuthReturnBus = OAuthReturnBus(), id: String = "a1") =
        AgentDetailViewModel(SavedStateHandle(mapOf("agentId" to id)), repo, bus)

    @Test
    fun loadsTheAgentAndItsTemplate() {
        val state = vm(FakeAgentRepository(listOf(testAgent()))).uiState.value
        assertEquals("Work", state.agent?.name)
        assertEquals("gmail-assistant", state.template?.id)
        assertFalse(state.isDirty)
        assertFalse(state.isLoading)
    }

    @Test
    fun missingAgentIsReportedAsNotFound() {
        val state = vm(FakeAgentRepository(listOf(testAgent("other")))).uiState.value
        assertNull(state.agent)
        assertTrue(state.notFound)
    }

    @Test
    fun editingMakesTheDraftDirtyAndDiscardRestoresIt() {
        val v = vm(FakeAgentRepository(listOf(testAgent())))
        v.onNameChange("Renamed")
        assertTrue(v.uiState.value.isDirty)
        assertTrue(v.uiState.value.canSave)

        v.onDiscard()
        assertFalse(v.uiState.value.isDirty)
        assertEquals("Work", v.uiState.value.name)
    }

    @Test
    fun saveSendsOnlyChangedFields() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val v = vm(repo)
        v.onNameChange("Renamed")
        v.onSave()

        val update = repo.lastUpdate!!
        assertEquals("Renamed", update.name)
        assertNull(update.instructions)
        assertNull(update.permissions)
        assertEquals(DetailMessage.Saved, (v.effects.first() as AgentDetailEffect.Message).text)
        assertFalse(v.uiState.value.isDirty)
    }

    @Test
    fun enablingANewPermissionOnAConnectedAgentAsksForConsentAgain() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val v = vm(repo)
        v.onToggle("organize_email", true)
        v.onSave()

        assertEquals(1, repo.connectCalls)
        v.effects.first() // saved message
        assertTrue(v.effects.first() is AgentDetailEffect.OpenConsent)
    }

    @Test
    fun changingOnlyTextDoesNotAskForConsent() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val v = vm(repo)
        v.onInstructionsChange("New instructions")
        v.onSave()
        assertEquals(0, repo.connectCalls)
    }

    @Test
    fun dangerousToggleCannotBeSetToAllow() {
        val v = vm(FakeAgentRepository(listOf(testAgent())))
        v.onToggle("send_email", true)
        v.onModeChange("send_email", ApprovalMode.Allow)
        assertEquals(null, v.uiState.value.permissions.approvalModes["gmail.send_email"]?.takeIf { it == ApprovalMode.Allow })
    }

    @Test
    fun saveFailureKeepsTheDraftAndShowsError() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent())).apply { updateResult = networkFailure }
        val v = vm(repo)
        v.onNameChange("Renamed")
        v.onSave()
        assertEquals(DataError.Network, v.uiState.value.error)
        assertEquals("Renamed", v.uiState.value.name)
        assertTrue(v.uiState.value.isDirty)
    }

    @Test
    fun pauseTogglesStatus() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent(status = AgentStatus.Active)))
        val v = vm(repo)
        v.onTogglePause()
        assertEquals(AgentStatus.Paused, repo.lastUpdate?.status)
        assertEquals(AgentStatus.Paused, v.uiState.value.agent?.status)
    }

    @Test
    fun deleteNeedsConfirmationThenEmitsDeleted() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val v = vm(repo)
        v.onDeleteRequest()
        assertTrue(v.uiState.value.showDeleteConfirm)
        assertNull(repo.deleted)

        v.onDeleteConfirm()
        assertEquals("a1", repo.deleted)
        assertTrue(v.effects.first() is AgentDetailEffect.Deleted)
    }

    @Test
    fun oauthReturnForThisAgentRefreshesAndReports() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val bus = OAuthReturnBus()
        val v = vm(repo, bus)
        val before = repo.refreshCount

        bus.publish(OAuthReturn("a1", ok = true))

        assertEquals(before + 1, repo.refreshCount)
        assertEquals(DetailMessage.Connected, (v.effects.first() as AgentDetailEffect.Message).text)
    }

    @Test
    fun oauthReturnForAnotherAgentIsIgnored() = runTest {
        val repo = FakeAgentRepository(listOf(testAgent()))
        val bus = OAuthReturnBus()
        vm(repo, bus)
        val before = repo.refreshCount
        bus.publish(OAuthReturn("someone-else", ok = true))
        assertEquals(before, repo.refreshCount)
    }
}
