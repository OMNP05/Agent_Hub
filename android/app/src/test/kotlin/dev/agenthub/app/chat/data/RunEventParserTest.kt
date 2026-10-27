package dev.agenthub.app.chat.data

import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEventParserTest {
    @Test
    fun parsesEveryDocumentedEvent() {
        assertEquals(RunEvent.Started("r1", "c1"), RunEventParser.parse("run.started", """{"run_id":"r1","conversation_id":"c1"}"""))
        assertEquals(RunEvent.Delta("Hi"), RunEventParser.parse("message.delta", """{"text":"Hi"}"""))
        assertEquals(RunEvent.ToolStarted("t1", "gmail.search_emails"), RunEventParser.parse("tool.started", """{"tool_call_id":"t1","tool":"gmail.search_emails"}"""))
        assertEquals(
            RunEvent.ToolFinished("t1", "gmail.search_emails", "Found 4", false),
            RunEventParser.parse("tool.finished", """{"tool_call_id":"t1","tool":"gmail.search_emails","summary":"Found 4","ok":false}"""),
        )
        assertEquals(RunEvent.ApprovalRequired("p1", "gmail.send_email"), RunEventParser.parse("approval.required", """{"approval_id":"p1","tool":"gmail.send_email"}"""))
        assertEquals(RunEvent.Completed("m9"), RunEventParser.parse("run.completed", """{"message_id":"m9"}"""))
        assertEquals(RunEvent.Failed(DataError.LlmKeyInvalid), RunEventParser.parse("run.error", """{"code":"llm_key_invalid"}"""))
    }

    @Test
    fun deltaKeepsWhitespaceAndUnicodeExactly() {
        assertEquals(RunEvent.Delta(" नमस्ते \n"), RunEventParser.parse("message.delta", "{\"text\":\" नमस्ते \\n\"}"))
    }

    @Test
    fun unknownTypesAndBadJsonAreIgnoredNotFatal() {
        assertEquals(RunEvent.Unknown, RunEventParser.parse("run.hologram", "{}"))
        assertEquals(RunEvent.Unknown, RunEventParser.parse("message.delta", "not json"))
        assertEquals(RunEvent.Unknown, RunEventParser.parse(null, "{}"))
        assertEquals(RunEvent.Unknown, RunEventParser.parse("message.delta", """{"text":{"nested":1}}"""))
        assertEquals(RunEvent.Unknown, RunEventParser.parse("tool.started", """{"tool":"x"}"""))
    }

    @Test
    fun toolFinishedDefaultsToOkAndEmptySummary() {
        assertEquals(RunEvent.ToolFinished("t", "x.y", "", true), RunEventParser.parse("tool.finished", """{"tool_call_id":"t","tool":"x.y"}"""))
    }

    @Test
    fun unknownErrorCodeBecomesBackendErrorNotRawText() {
        val event = RunEventParser.parse("run.error", """{"code":"quota_x"}""") as RunEvent.Failed
        assertEquals(DataError.Backend("quota_x"), event.error)
    }

    @Test
    fun onlyCompletionFailureAndApprovalAreTerminal() {
        assertTrue(RunEvent.Completed(null).isTerminal)
        assertTrue(RunEvent.Failed(DataError.Network).isTerminal)
        assertTrue(RunEvent.ApprovalRequired("p", "t").isTerminal)
        assertFalse(RunEvent.Delta("x").isTerminal)
        assertFalse(RunEvent.ToolStarted("t", "x").isTerminal)
    }
}
