package dev.agenthub.app.chat.ui

import androidx.lifecycle.SavedStateHandle
import dev.agenthub.app.agents.FakeAgentRepository
import dev.agenthub.app.agents.testAgent
import dev.agenthub.app.chat.FakeChatRepository
import dev.agenthub.app.chat.FakeChatStream
import dev.agenthub.app.chat.conversation
import dev.agenthub.app.chat.ev
import dev.agenthub.app.chat.failingAfter
import dev.agenthub.app.chat.hanging
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.model.MessageRole
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.core.model.RunState
import dev.agenthub.app.core.model.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private lateinit var chats: FakeChatRepository
    private lateinit var stream: FakeChatStream

    private fun vm(
        existing: List<dev.agenthub.app.core.model.Conversation> = listOf(conversation("c1", at = 1)),
    ): ChatViewModel {
        chats = FakeChatRepository().apply { conversations.value = existing }
        stream = FakeChatStream()
        val agents = FakeAgentRepository(listOf(testAgent("a1", name = "Work Gmail")))
        return ChatViewModel(SavedStateHandle(mapOf("agentId" to "a1")), chats, agents, stream)
    }

    private fun stored(id: String, seq: Long, role: MessageRole, text: String, conv: String = "c1") =
        Message(id, conv, seq, role, listOf(ContentBlock.Text(text)), Instant.EPOCH)

    @Test
    fun loadsNameAndSelectsTheMostRecentConversation() {
        val v = vm(listOf(conversation("old", at = 1), conversation("new", at = 5)).sortedByDescending { it.updatedAt })
        val s = v.uiState.value
        assertEquals("Work Gmail", s.agentName)
        assertEquals("new", s.currentConversationId)
        assertFalse(s.isLoading)
    }

    @Test
    fun noConversationsShowsEmptyState() {
        val v = vm(emptyList())
        assertTrue(v.uiState.value.isEmpty)
    }

    @Test
    fun storedHistoryIsShown() {
        val v = vm()
        chats.messages.value = listOf(stored("m1", 1, MessageRole.User, "hi"), stored("m2", 2, MessageRole.Assistant, "hello"))
        assertEquals(listOf("hi", "hello"), v.uiState.value.items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Assistant).text })
    }

    @Test
    fun failedFirstLoadWithNothingCachedIsABlockingError() {
        chats = FakeChatRepository().apply { refreshConversationsResult = AppResult.Failure(DataError.Network) }
        val v = ChatViewModel(SavedStateHandle(mapOf("agentId" to "a1")), chats, FakeAgentRepository(), FakeChatStream())
        assertEquals(DataError.Network, v.uiState.value.loadError)
    }

    @Test
    fun failedRefreshWithCachedConversationsStaysUsable() {
        chats = FakeChatRepository().apply {
            conversations.value = listOf(conversation("c1"))
            refreshConversationsResult = AppResult.Failure(DataError.Network)
        }
        val v = ChatViewModel(SavedStateHandle(mapOf("agentId" to "a1")), chats, FakeAgentRepository(), FakeChatStream())
        assertNull(v.uiState.value.loadError)
    }

    @Test
    fun sendStreamsTextAndToolCardsThenShowsStoredHistoryOnCompletion() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = {
            flow {
                emit(ev("1", RunEvent.Started("r1", "c1")))
                emit(ev("2", RunEvent.ToolStarted("t1", "github.list_my_pull_requests")))
                emit(ev("3", RunEvent.ToolFinished("t1", "github.list_my_pull_requests", "Fetched 3 open PRs", true)))
                emit(ev("4", RunEvent.Delta("You have ")))
                emit(ev("5", RunEvent.Delta("3 PRs.")))
                // The server stores the final messages before it announces completion.
                chats.messages.value = listOf(
                    stored("m1", 1, MessageRole.User, "Show my PRs"),
                    stored("m2", 2, MessageRole.Assistant, "You have 3 PRs."),
                )
                emit(ev("6", RunEvent.Completed("m2")))
            }
        }

        v.onInputChange("Show my PRs")
        v.onSend()
        advanceUntilIdle()

        assertEquals("Show my PRs", stream.sentMessage)
        assertEquals("c1", stream.sentConversation)
        assertFalse(v.uiState.value.isRunning)
        assertEquals("", v.uiState.value.input)
        assertEquals(listOf("User", "Assistant"), v.uiState.value.items.map { it::class.simpleName })
        assertEquals("You have 3 PRs.", (v.uiState.value.items[1] as ChatItem.Assistant).text)
    }

    @Test
    fun whileRunningTheLiveRowsAreShown() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c1")), ev("2", RunEvent.Delta("Thinking"))) }

        v.onInputChange("Hello")
        v.onSend()

        val s = v.uiState.value
        assertTrue(s.isRunning)
        assertFalse(s.canSend)
        assertEquals(listOf("User", "Assistant"), s.items.map { it::class.simpleName })
        assertTrue((s.items[1] as ChatItem.Assistant).isStreaming)
        v.onStop()
        advanceUntilIdle()
    }

    @Test
    fun blankOrDoubleSendIsIgnored() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c1"))) }

        v.onInputChange("   ")
        v.onSend()
        assertNull(stream.sentMessage)

        v.onInputChange("first")
        v.onSend()
        v.onInputChange("second")
        v.onSend()
        assertEquals("first", stream.sentMessage)
        v.onStop()
        advanceUntilIdle()
    }

    @Test
    fun sendWithNoConversationCreatesOneFirst() = runTest(dispatcher) {
        val v = vm(emptyList())
        stream.sendScript = { flow { emit(ev("1", RunEvent.Completed(null))) } }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals(1, chats.newConversationCalls)
        assertEquals("c101", stream.sentConversation)
    }

    @Test
    fun busyAgentShowsAnErrorRowAndAllowsSendingAgain() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { failingAfter(DataError.AgentBusy) }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertFalse(v.uiState.value.isRunning)
        assertEquals(DataError.AgentBusy, (v.uiState.value.items.last() as ChatItem.Failure).error)
        v.onInputChange("again")
        assertTrue(v.uiState.value.canSend)
    }

    @Test
    fun runErrorEventShowsAFriendlyFailureRow() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { flow { emit(ev("1", RunEvent.Started("r1", "c1"))); emit(ev("2", RunEvent.Failed(DataError.LlmKeyInvalid))) } }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals(DataError.LlmKeyInvalid, (v.uiState.value.items.last() as ChatItem.Failure).error)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun droppedConnectionResumesFromTheLastEventId() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = {
            failingAfter(DataError.Network, ev("1", RunEvent.Started("r1", "c1")), ev("2", RunEvent.Delta("Par")))
        }
        stream.resumeScript = { _, _ -> flow { emit(ev("3", RunEvent.Delta("tial"))); emit(ev("4", RunEvent.Completed("m"))) } }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals(1, stream.resumeCalls)
        assertEquals("r1", stream.resumedRun)
        assertEquals("2", stream.resumedFrom)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun givesUpAfterRepeatedDropsWithANetworkError() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { failingAfter(DataError.Network, ev("1", RunEvent.Started("r1", "c1"))) }
        stream.resumeScript = { _, _ -> failingAfter(DataError.Network) }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals(3, stream.resumeCalls)
        assertEquals(DataError.Network, (v.uiState.value.items.last() as ChatItem.Failure).error)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun failureBeforeTheRunStartedIsNotRetried() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { failingAfter(DataError.Network) }

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals(0, stream.resumeCalls)
        assertEquals(DataError.Network, (v.uiState.value.items.last() as ChatItem.Failure).error)
    }

    @Test
    fun streamClosedWithoutFinalEventAsksTheServerForTheRealRunState() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { flow { emit(ev("1", RunEvent.Started("r1", "c1"))); emit(ev("2", RunEvent.Delta("Done."))) } }
        chats.runStateResult = AppResult.Success(RunState("r1", RunStatus.Completed, null))
        chats.messages.value = listOf(stored("m1", 1, MessageRole.User, "Hi"), stored("m2", 2, MessageRole.Assistant, "Done."))

        v.onInputChange("Hi")
        v.onSend()
        advanceUntilIdle()

        assertEquals("Done.", (v.uiState.value.items.last() as ChatItem.Assistant).text)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun approvalPendingRowSurvivesTheEndOfTheStream() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = {
            flow {
                emit(ev("1", RunEvent.Started("r1", "c1")))
                emit(ev("2", RunEvent.ApprovalRequired("p1", "gmail.send_email")))
            }
        }

        v.onInputChange("Send the report")
        v.onSend()
        advanceUntilIdle()

        val pending = v.uiState.value.items.last() as ChatItem.ApprovalPending
        assertEquals("gmail.send_email", pending.tool)
        assertFalse(v.uiState.value.isRunning)
    }

    @Test
    fun stopCancelsTheRunOnTheServerAndUnlocksTheComposer() = runTest(dispatcher) {
        val v = vm()
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c1")), ev("2", RunEvent.ToolStarted("t1", "gmail.search_emails"))) }

        v.onInputChange("Search")
        v.onSend()
        assertTrue(v.uiState.value.isRunning)

        v.onStop()
        advanceUntilIdle()

        assertEquals("r1", chats.cancelledRun)
        assertFalse("composer must unlock even though the run job was cancelled", v.uiState.value.isRunning)
        val tool = v.uiState.value.items.filterIsInstance<ChatItem.Tool>().single()
        assertFalse(tool.isRunning)
        assertFalse(tool.ok)
    }

    @Test
    fun newChatOnAnEmptyConversationDoesNothing() = runTest(dispatcher) {
        val v = vm()
        v.onNewChat()
        advanceUntilIdle()
        assertEquals(0, chats.newConversationCalls)
    }

    @Test
    fun newChatStartsAFreshConversationAndClearsTheList() = runTest(dispatcher) {
        val v = vm()
        chats.messages.value = listOf(stored("m1", 1, MessageRole.User, "hi"))
        assertEquals(1, v.uiState.value.items.size)

        v.onNewChat()
        advanceUntilIdle()

        assertEquals(1, chats.newConversationCalls)
        assertEquals("c101", v.uiState.value.currentConversationId)
        assertTrue(v.uiState.value.items.isEmpty())
    }

    @Test
    fun deletingTheCurrentConversationSelectsAnother() = runTest(dispatcher) {
        val v = vm(listOf(conversation("c2", at = 5), conversation("c1", at = 1)))
        assertEquals("c2", v.uiState.value.currentConversationId)

        v.onDeleteConversation("c2")
        advanceUntilIdle()

        assertEquals("c2", chats.deletedId)
        assertEquals("c1", v.uiState.value.currentConversationId)
    }

    @Test
    fun clearHistoryNeedsConfirmationThenEmptiesEverything() = runTest(dispatcher) {
        val v = vm()
        v.onClearHistoryRequest()
        assertTrue(v.uiState.value.showClearConfirm)
        assertNull(chats.clearedAgent)

        v.onClearHistoryConfirm()
        advanceUntilIdle()

        assertEquals("a1", chats.clearedAgent)
        assertTrue(v.uiState.value.items.isEmpty())
        assertNull(v.uiState.value.currentConversationId)
    }

    @Test
    fun conversationActionsAreBlockedWhileARunIsActive() = runTest(dispatcher) {
        val v = vm(listOf(conversation("c2", at = 5), conversation("c1", at = 1)))
        stream.sendScript = { hanging(ev("1", RunEvent.Started("r1", "c2"))) }
        v.onInputChange("work")
        v.onSend()

        v.onSelectConversation("c1")
        v.onDeleteConversation("c2")
        v.onNewChat()

        assertEquals("c2", v.uiState.value.currentConversationId)
        assertNull(chats.deletedId)
        v.onStop()
        advanceUntilIdle()
    }
}
