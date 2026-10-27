package dev.agenthub.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    @Test
    fun parsesAnApprovalPush() {
        assertEquals(
            ApprovalPush("p1", "Work Gmail"),
            PushPayload.parse(mapOf("type" to "approval", "approval_id" to "p1", "agent_name" to "Work Gmail")),
        )
    }

    @Test
    fun rejectsOtherTypesAndMissingOrOversizedIds() {
        assertNull(PushPayload.parse(mapOf("type" to "promo", "approval_id" to "p1")))
        assertNull(PushPayload.parse(mapOf("type" to "approval")))
        assertNull(PushPayload.parse(mapOf("type" to "approval", "approval_id" to "  ")))
        assertNull(PushPayload.parse(mapOf("type" to "approval", "approval_id" to "x".repeat(101))))
        assertNull(PushPayload.parse(emptyMap()))
    }

    @Test
    fun agentNameIsCleanedAndLimited() {
        val push = PushPayload.parse(
            mapOf("type" to "approval", "approval_id" to "p1", "agent_name" to "Bad\nName\u0007" + "y".repeat(100)),
        )!!
        assertEquals(60, push.agentName.length)
        assertEquals(false, push.agentName.contains('\n'))
        assertEquals(false, push.agentName.contains('\u0007'))
    }

    @Test
    fun missingAgentNameIsAllowed() {
        assertEquals("", PushPayload.parse(mapOf("type" to "approval", "approval_id" to "p1"))!!.agentName)
    }

    @Test
    fun toolArgumentsInThePayloadAreNeverCarried() {
        val push = PushPayload.parse(
            mapOf("type" to "approval", "approval_id" to "p1", "agent_name" to "A", "args" to "secret body", "body" to "secret"),
        )!!
        assertEquals(ApprovalPush("p1", "A"), push)
    }
}
