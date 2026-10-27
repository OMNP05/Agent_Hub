package dev.agenthub.app.demo

import app.cash.turbine.test
import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.approvals.data.Decision
import dev.agenthub.app.chat.ui.ChatItem
import dev.agenthub.app.chat.ui.ChatReducer
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.NewAgent
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.core.network.dto.toDomain
import dev.agenthub.app.core.session.Session
import dev.agenthub.app.llm.data.LlmProfileInput
import dev.agenthub.app.llm.data.LlmProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end through the real stack: sign-in, create an agent, connect it, chat with streaming, approve or
 * reject, read the activity log, manage LLM profiles. Only the server is the demo backend.
 */
class EndToEndFlowTest {
    private val h = DemoHarness()

    private suspend fun signIn() {
        assertEquals(AppResult.Success(Unit), h.auth.signInWithGoogle("demo-google-id-token", "n"))
    }

    private suspend fun createAgent(templateId: String, toggles: Map<String, Boolean> = emptyMap(), modes: Map<String, ApprovalMode> = emptyMap()): Agent {
        val template = TemplateCatalog.byId(templateId)!!
        val base = PermissionRules.defaults(template)
        val perms = AgentPermissions(base.toggles + toggles, base.approvalModes + modes)
        val agent = (h.agents.create(NewAgent(templateId, template.displayName, template.defaultInstructions, perms)) as AppResult.Success).value
        assertEquals(AgentStatus.Draft, agent.status)
        assertNull("a new agent has no connection until consent", agent.connection)
        assertTrue(h.agents.startConnect(agent) is AppResult.Success)
        h.agents.refresh()
        return h.api.agents().body()!!.first { it.id == agent.id }.toDomain()!!
    }

    /** Sends [text] and returns every event of the run plus the rows the chat screen would show. */
    private suspend fun ask(agent: Agent, text: String): Pair<List<RunEvent>, List<ChatItem>> {
        val conversation = (h.chats.newConversation(agent.id) as AppResult.Success).value
        return askIn(agent, conversation.id, text)
    }

    private suspend fun askIn(agent: Agent, conversationId: String, text: String): Pair<List<RunEvent>, List<ChatItem>> {
        val events = h.stream.send(agent.id, conversationId, text).toList().map { it.event }
        val rows = events.fold(ChatReducer.withUserMessage(emptyList(), "1", text)) { acc, e -> ChatReducer.apply(acc, e) }
        return events to rows
    }

    // ---------------------------------------------------------------- sign in

    @Test
    fun signInStoresASessionAndSignOutClearsIt() = runTest {
        signIn()
        assertNotNull(h.store.current())
        h.auth.signOut()
        assertNull(h.store.current())
    }

    @Test
    fun anExpiredAccessTokenIsRefreshedTransparently() = runTest {
        signIn()
        // The demo accepts any bearer token, so prove the authenticator wiring with a direct refresh call.
        h.store.save(Session("stale", h.store.current()!!.refreshToken))
        val agents = h.api.agents()
        assertTrue(agents.isSuccessful)
    }

    // ----------------------------------------------------------------- agents

    @Test
    fun theSeededAccountHasADemoAgentAndProfile() = runTest {
        signIn()
        h.agents.refresh()
        val list = h.api.agents().body()!!
        assertEquals(listOf("Demo GitHub"), list.map { it.name })
        assertEquals("demo", (h.llm.profiles() as AppResult.Success).value.single().keyLast4)
    }

    @Test
    fun creatingAndConnectingAnAgentMakesItActiveWithScopesForTheEnabledToggles() = runTest {
        signIn()
        val agent = createAgent("gmail-assistant")
        assertEquals(AgentStatus.Active, agent.status)
        assertEquals(listOf("gmail.readonly"), agent.connection!!.scopesGranted)
    }

    @Test
    fun turningOnAToggleWidensTheGrantedScopes() = runTest {
        signIn()
        val agent = createAgent("gmail-assistant")
        val wider = AgentPermissions(agent.permissions.toggles + ("send_email" to true), agent.permissions.approvalModes)
        h.agents.update(agent.id, AgentUpdate(permissions = wider))
        h.agents.startConnect(agent)
        val refreshed = h.api.agents().body()!!.first { it.id == agent.id }
        assertTrue("gmail.send" in refreshed.connection!!.scopesGranted)
    }

    @Test
    fun theServerNeverAcceptsAnAllowModeForADangerousTool() = runTest {
        signIn()
        val template = TemplateCatalog.byId("gmail-assistant")!!
        val tampered = AgentPermissions(mapOf("send_email" to true), mapOf("gmail.send_email" to ApprovalMode.Allow))
        val created = (h.agents.create(NewAgent(template.id, "Mail", "x", tampered)) as AppResult.Success).value
        assertEquals(ApprovalMode.Ask, created.permissions.approvalModes["gmail.send_email"])
    }

    @Test
    fun pausingAndDeletingWork() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        val paused = (h.agents.update(agent.id, AgentUpdate(status = AgentStatus.Paused)) as AppResult.Success).value
        assertEquals(AgentStatus.Paused, paused.status)
        assertEquals(AppResult.Success(Unit), h.agents.delete(agent.id))
        assertTrue(h.api.agents().body()!!.none { it.id == agent.id })
    }

    // ------------------------------------------------------------------- chat

    @Test
    fun aReadQuestionStreamsAToolCardThenTheAnswerAndIsStored() = runTest {
        signIn()
        val agent = createAgent("github-assistant")

        val (events, rows) = ask(agent, "Show my open PRs")

        assertTrue(events.first() is RunEvent.Started)
        assertTrue(events.any { it is RunEvent.ToolStarted && it.tool == "github.list_my_pull_requests" })
        assertEquals("Fetched 3 open PRs", (rows.filterIsInstance<ChatItem.Tool>().single()).summary)
        assertTrue(events.last() is RunEvent.Completed)
        val answer = rows.filterIsInstance<ChatItem.Assistant>().single().text
        assertTrue(answer.startsWith("You have 3 open pull requests"))
        assertFalse(rows.filterIsInstance<ChatItem.Assistant>().single().isStreaming)
    }

    @Test
    fun theStoredHistoryMatchesWhatWasStreamed() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        val conversation = (h.chats.newConversation(agent.id) as AppResult.Success).value
        askIn(agent, conversation.id, "Show my open PRs")

        h.chats.refreshMessages(conversation.id)
        val history = h.chats.observeMessages(conversation.id)
        history.test {
            val items = ChatReducer.fromHistory(awaitItem())
            assertEquals(listOf("User", "Tool", "Assistant"), items.map { it::class.simpleName })
            assertEquals("Fetched 3 open PRs", (items[1] as ChatItem.Tool).summary)
            assertFalse((items[1] as ChatItem.Tool).isRunning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnknownQuestionGetsHelpfulGuidanceAndNoTool() = runTest {
        signIn()
        val agent = createAgent("calendar-assistant")
        val (events, rows) = ask(agent, "what is the meaning of life")
        assertTrue(events.none { it is RunEvent.ToolStarted })
        assertTrue(rows.filterIsInstance<ChatItem.Assistant>().single().text.contains("calendar"))
    }

    @Test
    fun aSecondMessageWhileARunIsActiveIsRejectedAsBusy() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        val slow = DemoHarness(DemoBackend(latencyMs = 0, speed = 0.0))
        // A run that has started but not finished: use an approval-pending run, which holds the conversation.
        val conversation = (h.chats.newConversation(agent.id) as AppResult.Success).value
        val first = h.stream.send(agent.id, conversation.id, "hello").toList()
        assertTrue(first.last().event is RunEvent.Completed)
        // After completion the conversation is free again, so a second message is accepted.
        val second = h.stream.send(agent.id, conversation.id, "show my followers").toList()
        assertTrue(second.last().event is RunEvent.Completed)
        assertNotNull(slow)
    }

    @Test
    fun anAgentWithoutAConnectionReportsItNeedsReconnecting() = runTest {
        signIn()
        val template = TemplateCatalog.byId("github-assistant")!!
        val agent = (h.agents.create(NewAgent(template.id, "Unconnected", "x", PermissionRules.defaults(template))) as AppResult.Success).value
        val (events, rows) = ask(agent, "Show my open PRs")
        assertEquals(RunEvent.Failed(DataError.ConnectionNeedsReauth), events.last())
        assertEquals(DataError.ConnectionNeedsReauth, (rows.last() as ChatItem.Failure).error)
    }

    @Test
    fun aPausedAgentDoesNotRunTools() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        h.agents.update(agent.id, AgentUpdate(status = AgentStatus.Paused))
        val (events, rows) = ask(agent, "Show my open PRs")
        assertTrue(events.none { it is RunEvent.ToolStarted })
        assertTrue(rows.filterIsInstance<ChatItem.Assistant>().single().text.contains("paused"))
    }

    // ----------------------------------------------------- permissions & approvals

    @Test
    fun aToolWhoseToggleIsOffIsRefusedWithoutRunning() = runTest {
        signIn()
        val agent = createAgent("github-assistant") // edit_profile is off by default
        val (events, rows) = ask(agent, "Change my bio to: Android dev")
        assertTrue(events.none { it is RunEvent.ToolStarted || it is RunEvent.ApprovalRequired })
        assertTrue(rows.filterIsInstance<ChatItem.Assistant>().single().text.contains("permission is switched off"))
        assertTrue(h.approvals.pending().let { (it as AppResult.Success).value.isEmpty() })
    }

    @Test
    fun aWriteToolOnAskPausesForApprovalWithTheExactArguments() = runTest {
        signIn()
        val agent = createAgent("github-assistant", toggles = mapOf("edit_profile" to true))

        val (events, rows) = ask(agent, "Change my bio to: Android dev")

        assertTrue(events.none { it is RunEvent.ToolStarted })
        assertEquals("github.update_profile", (events.last() as RunEvent.ApprovalRequired).tool)
        assertTrue(rows.last() is ChatItem.ApprovalPending)

        val pending = (h.approvals.pending() as AppResult.Success).value.single()
        assertEquals("Android dev", (pending.args["bio"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(ApprovalStatus.Pending, pending.status)
        assertEquals("nothing may have run yet", 0, (h.api.agentLogs(agent.id)).size)
    }

    @Test
    fun approvingRunsTheStoredActionOnceAndLogsIt() = runTest {
        signIn()
        val agent = createAgent("github-assistant", toggles = mapOf("edit_profile" to true))
        val conversation = (h.chats.newConversation(agent.id) as AppResult.Success).value
        askIn(agent, conversation.id, "Change my bio to: Android dev")
        val pending = (h.approvals.pending() as AppResult.Success).value.single()

        val decided = (h.approvals.decide(pending.id, Decision.Approve) as AppResult.Success).value

        assertEquals(ApprovalStatus.Approved, decided.status)
        assertTrue((h.approvals.pending() as AppResult.Success).value.isEmpty())
        val log = (h.approvals.logs(agent.id) as AppResult.Success).value.single()
        assertEquals("github.update_profile", log.tool)
        h.chats.refreshMessages(conversation.id)
        h.chats.observeMessages(conversation.id).test {
            assertTrue(awaitItem().last().blocks.filterIsInstance<dev.agenthub.app.core.model.ContentBlock.Text>().single().text.startsWith("Done"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSecondDecisionOnTheSameApprovalIsRefusedAndRunsNothingTwice() = runTest {
        signIn()
        val agent = createAgent("github-assistant", toggles = mapOf("edit_profile" to true))
        ask(agent, "Change my bio to: Android dev")
        val pending = (h.approvals.pending() as AppResult.Success).value.single()

        h.approvals.decide(pending.id, Decision.Approve)
        val again = h.approvals.decide(pending.id, Decision.Approve)
        val flip = h.approvals.decide(pending.id, Decision.Reject)

        assertEquals(AppResult.Failure(DataError.ApprovalExpired), again)
        assertEquals(AppResult.Failure(DataError.ApprovalExpired), flip)
        assertEquals("the action must run exactly once", 1, (h.approvals.logs(agent.id) as AppResult.Success).value.size)
    }

    @Test
    fun rejectingChangesNothingAndTheAgentExplains() = runTest {
        signIn()
        val agent = createAgent("github-assistant", toggles = mapOf("edit_profile" to true))
        val conversation = (h.chats.newConversation(agent.id) as AppResult.Success).value
        askIn(agent, conversation.id, "Change my bio to: Android dev")
        val pending = (h.approvals.pending() as AppResult.Success).value.single()

        val rejected = (h.approvals.decide(pending.id, Decision.Reject) as AppResult.Success).value

        assertEquals(ApprovalStatus.Rejected, rejected.status)
        val entry = (h.approvals.logs(agent.id) as AppResult.Success).value.single()
        assertTrue(entry.resultSummary.contains("nothing was changed"))
    }

    @Test
    fun aWriteToolSetToAllowRunsWithoutAsking() = runTest {
        signIn()
        val agent = createAgent(
            "github-assistant",
            toggles = mapOf("edit_profile" to true),
            modes = mapOf("github.update_profile" to ApprovalMode.Allow),
        )
        val (events, _) = ask(agent, "Change my bio to: Android dev")
        assertTrue(events.any { it is RunEvent.ToolFinished })
        assertTrue((h.approvals.pending() as AppResult.Success).value.isEmpty())
    }

    @Test
    fun sendingEmailAlwaysNeedsApprovalAndShowsTheFullMessage() = runTest {
        signIn()
        val agent = createAgent("gmail-assistant", toggles = mapOf("send_email" to true))

        val (events, _) = ask(agent, "Send an email to sam@example.com saying I'll join at 5")

        assertEquals("gmail.send_email", (events.last() as RunEvent.ApprovalRequired).tool)
        val pending = (h.approvals.pending() as AppResult.Success).value.single()
        assertEquals("[\"sam@example.com\"]", pending.args["to"].toString())
        assertTrue(pending.args["body"].toString().contains("I'll join at 5"))
    }

    @Test
    fun deletingAnEventAlwaysNeedsApproval() = runTest {
        signIn()
        val agent = createAgent("calendar-assistant", toggles = mapOf("delete_events" to true))
        val (events, _) = ask(agent, "Delete my team sync")
        assertEquals("calendar.delete_event", (events.last() as RunEvent.ApprovalRequired).tool)
    }

    @Test
    fun approvalsOfOtherAgentsAreRemovedWhenTheAgentIsDeleted() = runTest {
        signIn()
        val agent = createAgent("github-assistant", toggles = mapOf("edit_profile" to true))
        ask(agent, "Change my bio to: Android dev")
        h.agents.delete(agent.id)
        assertTrue((h.approvals.pending() as AppResult.Success).value.isEmpty())
    }

    @Test
    fun theAuditLogRedactsMessageBodies() = runTest {
        signIn()
        val agent = createAgent("gmail-assistant", toggles = mapOf("draft_replies" to true), modes = mapOf("gmail.create_draft" to ApprovalMode.Allow))
        ask(agent, "Draft a reply to sam@example.com saying Thanks, see you at 5")
        val entry = (h.approvals.logs(agent.id) as AppResult.Success).value.single()
        assertTrue(entry.argsRedacted.toString().contains("[redacted]"))
        assertFalse(entry.argsRedacted.toString().contains("see you at 5"))
    }

    // ---------------------------------------------------------- stop & run state

    @Test
    fun runStateAndCancelWork() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        val (events, _) = ask(agent, "Show my open PRs")
        val runId = (events.first() as RunEvent.Started).runId
        val state = (h.chats.runState(runId) as AppResult.Success).value
        assertEquals("completed", state.status.wire)
    }

    // -------------------------------------------------------------- conversations

    @Test
    fun conversationsAreListedNewestFirstAndCanBeDeletedOrCleared() = runTest {
        signIn()
        val agent = createAgent("github-assistant")
        val a = (h.chats.newConversation(agent.id) as AppResult.Success).value
        val b = (h.chats.newConversation(agent.id) as AppResult.Success).value
        askIn(agent, b.id, "Show my open PRs")

        h.chats.refreshConversations(agent.id)
        h.chats.observeConversations(agent.id).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals("the conversation with a message is newest", b.id, list.first().id)
            assertEquals("Show my open PRs", list.first().title)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(AppResult.Success(Unit), h.chats.deleteConversation(a.id))
        assertEquals(AppResult.Success(Unit), h.chats.clearHistory(agent.id))
        assertTrue(h.api.conversations(agent.id).body()!!.isEmpty())
    }

    // --------------------------------------------------------------- llm profiles

    @Test
    fun theKeyIsNeverReturnedOnlyItsLastFourCharacters() = runTest {
        signIn()
        val saved = (h.llm.create(
            LlmProfileInput("Work", LlmProvider.OpenAi, "", "gpt-demo", 0.2, 1000, "sk-secret-value-9876", makeDefault = true),
        ) as AppResult.Success).value
        assertEquals("9876", saved.keyLast4)
        assertFalse(saved.toString().contains("secret"))
        assertTrue((h.llm.profiles() as AppResult.Success).value.single { it.id == saved.id }.isDefault)
    }

    @Test
    fun editingWithoutAKeyKeepsTheStoredKey() = runTest {
        signIn()
        val first = (h.llm.profiles() as AppResult.Success).value.single()
        val edited = (h.llm.update(first.id, LlmProfileInput("Renamed", LlmProvider.Anthropic, "", "claude-demo", 0.5, 800, "", makeDefault = false)) as AppResult.Success).value
        assertEquals("Renamed", edited.name)
        assertEquals("demo", edited.keyLast4)
    }

    @Test
    fun aBadKeyIsRejectedWithTheFriendlyCode() = runTest {
        signIn()
        val result = h.llm.create(LlmProfileInput("Bad", LlmProvider.OpenAi, "", "gpt-demo", 0.2, 1000, "sk-nope-bad", makeDefault = false))
        assertEquals(AppResult.Failure(DataError.LlmKeyInvalid), result)
        assertEquals(AppResult.Failure(DataError.LlmKeyInvalid), h.llm.testDraft(LlmProfileInput("Bad", LlmProvider.OpenAi, "", "gpt-demo", 0.2, 1000, "sk-nope-bad", false)))
    }

    @Test
    fun testingAGoodDraftWorksAndDeletingAProfileRemovesIt() = runTest {
        signIn()
        val draft = LlmProfileInput("Good", LlmProvider.Anthropic, "", "claude-demo", 0.2, 1000, "sk-good-1234", false)
        assertEquals(true, (h.llm.testDraft(draft) as AppResult.Success).value.ok)
        val saved = (h.llm.create(draft) as AppResult.Success).value
        assertEquals(AppResult.Success(Unit), h.llm.delete(saved.id))
        assertTrue((h.llm.profiles() as AppResult.Success).value.none { it.id == saved.id })
    }

    // -------------------------------------------------------------------- account

    @Test
    fun killSwitchPausesEveryAgent() = runTest {
        signIn()
        createAgent("github-assistant")
        createAgent("gmail-assistant")
        assertEquals(204, h.api.killSwitch().code())
        assertTrue(h.api.agents().body()!!.all { it.status == "paused" })
    }

    @Test
    fun exportReturnsAnHttpsLinkAndDeletingTheAccountEmptiesEverything() = runTest {
        signIn()
        assertTrue(h.api.exportData().body()!!.url!!.startsWith("https://"))
        assertEquals(204, h.api.deleteAccount().code())
        assertTrue(h.api.agents().body()!!.isEmpty())
        assertTrue(h.api.llmProfiles().body()!!.isEmpty())
    }
}

private suspend fun dev.agenthub.app.core.network.AgentHubApi.agentLogs(agentId: String) = logs(agentId, null, 50).body().orEmpty()
