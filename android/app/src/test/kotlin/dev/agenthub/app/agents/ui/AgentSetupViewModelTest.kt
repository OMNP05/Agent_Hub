package dev.agenthub.app.agents.ui

import androidx.lifecycle.SavedStateHandle
import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.networkFailure
import dev.agenthub.app.agents.ui.setup.AgentSetupEffect
import dev.agenthub.app.agents.ui.setup.AgentSetupViewModel
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.navigation.AgentSetupRoute
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
class AgentSetupViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeAgentRepository, templateId: String = "gmail-assistant") =
        AgentSetupViewModel(SavedStateHandle(mapOf("templateId" to templateId)), repo)

    @Test
    fun startsFromTemplateDefaults() {
        val state = vm(FakeAgentRepository()).uiState.value
        assertEquals("Gmail Assistant", state.name)
        assertEquals(true, state.permissions.toggles["read_email"])
        assertEquals(false, state.permissions.toggles["send_email"])
        assertEquals(ApprovalMode.Ask, state.permissions.approvalModes["gmail.create_draft"])
        assertTrue(state.canSave)
    }

    @Test
    fun unknownTemplateCannotBeSaved() {
        val state = vm(FakeAgentRepository(), templateId = "custom").uiState.value
        assertNull(state.template)
        assertFalse(state.canSave)
    }

    @Test
    fun blankNameCannotBeSaved() {
        val v = vm(FakeAgentRepository())
        v.onNameChange("   ")
        assertFalse(v.uiState.value.canSave)
    }

    @Test
    fun saveCreatesAgentWithSanitizedPermissionsThenStartsConsent() = runTest {
        val repo = FakeAgentRepository()
        val v = vm(repo)
        v.onToggle("send_email", true)
        v.onModeChange("draft_replies", ApprovalMode.Allow)

        v.onSave()

        val created = repo.lastCreated!!
        assertEquals("gmail-assistant", created.templateId)
        assertEquals(true, created.permissions.toggles["send_email"])
        // Dangerous tools stay on Ask no matter what, and drafts are the one Write tool set to Allow.
        assertEquals(ApprovalMode.Ask, created.permissions.approvalModes["gmail.send_email"])
        assertEquals(ApprovalMode.Allow, created.permissions.approvalModes["gmail.create_draft"])
        assertNotNull(created.timeZone)

        val effect = v.effects.first()
        assertTrue(effect is AgentSetupEffect.OpenConsent)
        assertEquals("https://provider.example/consent", (effect as AgentSetupEffect.OpenConsent).url)
        assertFalse(v.uiState.value.isSaving)
    }

    @Test
    fun createFailureShowsErrorAndDoesNotStartConsent() = runTest {
        val repo = FakeAgentRepository().apply { createResult = networkFailure }
        val v = vm(repo)
        v.onSave()
        assertEquals(DataError.Network, v.uiState.value.error)
        assertEquals(0, repo.connectCalls)
        assertFalse(v.uiState.value.isSaving)
    }

    @Test
    fun consentFailureStillKeepsTheCreatedAgent() = runTest {
        val repo = FakeAgentRepository().apply { connectResult = AppResult.Failure(DataError.Unknown) }
        val v = vm(repo)
        v.onSave()
        val effect = v.effects.first()
        assertTrue(effect is AgentSetupEffect.CreatedWithoutConsent)
        assertEquals("new", (effect as AgentSetupEffect.CreatedWithoutConsent).agentId)
    }
}
