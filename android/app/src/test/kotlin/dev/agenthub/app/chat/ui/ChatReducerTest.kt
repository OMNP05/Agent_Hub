package dev.agenthub.app.chat.ui

import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.model.MessageRole
import dev.agenthub.app.core.model.RunEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatReducerTest {
    private fun apply(vararg events: RunEvent, start: List<ChatItem> = emptyList()) =
        events.fold(start) { acc, e -> ChatReducer.apply(acc, e) }

    @Test
    fun deltasAppendToOneStreamingAssistantRow() {
        val items = apply(RunEvent.Delta("Hel"), RunEvent.Delta("lo"))
        val row = items.single() as ChatItem.Assistant
        assertEquals("Hello", row.text)
        assertTrue(row.isStreaming)
    }

    @Test
    fun completionStopsStreaming() {
        val items = apply(RunEvent.Delta("Hi"), RunEvent.Completed("m1"))
        assertFalse((items.single() as ChatItem.Assistant).isStreaming)
    }

    @Test
    fun toolStartedThenFinishedUpdatesTheSameCard() {
        val items = apply(
            RunEvent.ToolStarted("t1", "github.list_my_pull_requests"),
            RunEvent.ToolFinished("t1", "github.list_my_pull_requests", "Fetched 3 open PRs", ok = true),
        )
        val card = items.single() as ChatItem.Tool
        assertEquals("Fetched 3 open PRs", card.summary)
        assertFalse(card.isRunning)
    }

    @Test
    fun runningToolShowsNoSummaryYet() {
        val card = apply(RunEvent.ToolStarted("t1", "gmail.search_emails")).single() as ChatItem.Tool
        assertTrue(card.isRunning)
    }

    @Test
    fun finishWithoutStartStillShowsACard() {
        val items = apply(RunEvent.ToolFinished("t9", "calendar.list_events", "Found 2 events", ok = true))
        assertEquals("Found 2 events", (items.single() as ChatItem.Tool).summary)
    }

    @Test
    fun textAfterAToolStartsANewAssistantRow() {
        val items = apply(
            RunEvent.Delta("Let me check."),
            RunEvent.ToolStarted("t1", "x.y"),
            RunEvent.ToolFinished("t1", "x.y", "done", true),
            RunEvent.Delta("Here you go."),
        )
        assertEquals(listOf("Assistant", "Tool", "Assistant"), items.map { it::class.simpleName })
        assertFalse((items[0] as ChatItem.Assistant).isStreaming)
        assertTrue((items[2] as ChatItem.Assistant).isStreaming)
    }

    @Test
    fun approvalRequiredAddsAPendingRowAndStopsStreaming() {
        val items = apply(RunEvent.Delta("Sending."), RunEvent.ApprovalRequired("p1", "gmail.send_email"))
        assertFalse((items[0] as ChatItem.Assistant).isStreaming)
        val pending = items[1] as ChatItem.ApprovalPending
        assertEquals("p1", pending.approvalId)
        assertEquals("gmail.send_email", pending.tool)
    }

    @Test
    fun failureAddsAnErrorRowWithoutRawText() {
        val items = apply(RunEvent.Delta("Working"), RunEvent.Failed(DataError.LlmKeyInvalid))
        assertEquals(DataError.LlmKeyInvalid, (items.last() as ChatItem.Failure).error)
        assertFalse((items.first() as ChatItem.Assistant).isStreaming)
    }

    @Test
    fun startedAndUnknownEventsChangeNothing() {
        val start = listOf<ChatItem>(ChatItem.User("u", "hi"))
        assertEquals(start, apply(RunEvent.Started("r", "c"), RunEvent.Unknown, start = start))
    }

    @Test
    fun settleMarksUnfinishedToolsAsStopped() {
        val items = ChatReducer.settle(apply(RunEvent.Delta("x"), RunEvent.ToolStarted("t1", "a.b")))
        val tool = items.filterIsInstance<ChatItem.Tool>().single()
        assertFalse(tool.isRunning)
        assertFalse(tool.ok)
        assertTrue(items.filterIsInstance<ChatItem.Assistant>().none { it.isStreaming })
    }

    @Test
    fun userMessageIsAppendedWithALocalKey() {
        val items = ChatReducer.withUserMessage(emptyList(), "1", "Show my PRs")
        assertEquals("local-1", items.single().key)
    }

    private fun msg(id: String, seq: Long, role: MessageRole, vararg blocks: ContentBlock) =
        Message(id, "c1", seq, role, blocks.toList(), Instant.EPOCH)

    @Test
    fun historyFoldsToolCallAndResultIntoOneCard() {
        val items = ChatReducer.fromHistory(
            listOf(
                msg("1", 1, MessageRole.User, ContentBlock.Text("Show my PRs")),
                msg("2", 2, MessageRole.Assistant, ContentBlock.ToolCall("t1", "github.list_my_pull_requests", JsonObject(emptyMap()))),
                msg("3", 3, MessageRole.Tool, ContentBlock.ToolResultSummary("t1", "Fetched 3 open PRs")),
                msg("4", 4, MessageRole.Assistant, ContentBlock.Text("You have 3 open PRs.")),
            ),
        )
        assertEquals(listOf("User", "Tool", "Assistant"), items.map { it::class.simpleName })
        assertEquals("Fetched 3 open PRs", (items[1] as ChatItem.Tool).summary)
        assertEquals("github.list_my_pull_requests", (items[1] as ChatItem.Tool).tool)
    }

    @Test
    fun historyOrdersBySeqEvenIfDeliveredOutOfOrder() {
        val items = ChatReducer.fromHistory(
            listOf(
                msg("b", 2, MessageRole.Assistant, ContentBlock.Text("second")),
                msg("a", 1, MessageRole.User, ContentBlock.Text("first")),
            ),
        )
        assertEquals(listOf("first", "second"), items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Assistant).text })
    }

    @Test
    fun storedToolCallWithoutResultIsNotShownAsRunning() {
        val items = ChatReducer.fromHistory(
            listOf(msg("2", 1, MessageRole.Assistant, ContentBlock.ToolCall("t1", "a.b", JsonObject(emptyMap())))),
        )
        assertFalse((items.single() as ChatItem.Tool).isRunning)
    }

    @Test
    fun historyKeysAreUnique() {
        val items = ChatReducer.fromHistory(
            listOf(
                msg("1", 1, MessageRole.Assistant, ContentBlock.Text("a"), ContentBlock.Text("b")),
                msg("2", 2, MessageRole.User, ContentBlock.Text("c")),
            ),
        )
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }
}
