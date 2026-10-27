package dev.agenthub.app.chat.ui

import androidx.lifecycle.SavedStateHandle
import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.testAgent
import dev.agenthub.app.chat.FakeChatRepository
import dev.agenthub.app.chat.FakeChatStream
import dev.agenthub.app.chat.conversation
import dev.agenthub.app.chat.ev
import dev.agenthub.app.chat.hanging
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.shell.ActiveAgent
import dev.agenthub.app.shell.ActiveAgentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class ChatVoiceDraftTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val chats = FakeChatRepository().apply { conversations.value = listOf(conversation("c1")) }
    private val stream = FakeChatStream()
    private val active = ActiveAgentStore()
    private fun vm(agents: FakeAgentRepository = FakeAgentRepository(listOf(testAgent("a1", name = "Work Gmail")))) =
        ChatViewModel(SavedStateHandle(mapOf("agentId" to "a1")), chats, agents, stream, active)

    @Test
    fun aTranscriptFillsTheBoxButIsNeverSentByItself() = runTest(dispatcher) {
        val v = vm()
        v.onTranscript("show my open PRs")

        assertEquals("show my open PRs", v.uiState.value.input)
        assertTrue(v.uiState.value.voiceDraft)
        assertNull("nothing may be sent until the user taps Send", stream.sentMessage)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun aTranscriptIsAppendedToWhatWasAlreadyTyped() {
        val v = vm()
        v.onInputChange("Please")
        v.onTranscript("archive the newsletters")
        assertEquals("Please archive the newsletters", v.uiState.value.input)
    }

    @Test
    fun editingTheDraftMakesItATypedMessageAgain() {
        val v = vm()
        v.onTranscript("show my PRs")
        v.onInputChange("show my PRs today")
        assertFalse(v.uiState.value.voiceDraft)
    }

    @Test
    fun sendingAVoiceDraftMarksTheReplyToBeReadAloud() = runTest(dispatcher) {
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c1"))) }
        val v = vm()
        v.onTranscript("show my PRs")
        v.onSend()

        assertTrue(v.uiState.value.replyByVoice)
        assertEquals("show my PRs", stream.sentMessage)
        v.onStop()
        advanceUntilIdle()
    }

    @Test
    fun aTypedMessageIsNotReadAloud() = runTest(dispatcher) {
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c1"))) }
        val v = vm()
        v.onInputChange("show my PRs")
        v.onSend()

        assertFalse(v.uiState.value.replyByVoice)
        v.onStop()
        advanceUntilIdle()
    }

    @Test
    fun lastAssistantTextIsAvailableForReadingAloud() {
        val v = vm()
        assertNull(v.lastAssistantText())
    }

    @Test
    fun openingAChatMakesItsAgentTheMiniBarAgent() {
        vm()
        assertEquals(ActiveAgent("a1", "Work Gmail"), active.agent.value)
    }

    @Test
    fun aDeletedAgentIsRemovedFromTheMiniBar() {
        val agents = FakeAgentRepository(listOf(testAgent("a1", name = "Work Gmail")))
        vm(agents)
        assertEquals("a1", active.agent.value?.id)

        agents.agents.value = listOf(testAgent("other"))

        assertNull(active.agent.value)
    }
}
