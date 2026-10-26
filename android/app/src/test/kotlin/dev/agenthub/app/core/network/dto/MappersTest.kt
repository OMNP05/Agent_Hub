package dev.agenthub.app.core.network.dto

import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.ConnectionStatus
import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.model.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MappersTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun agent(connector: String = "github", status: String = "active") = AgentDto(
        id = "a1", name = "Work", templateId = "github-assistant", connector = connector, status = status,
        toggles = mapOf("read_prs" to true), approvalModes = mapOf("github.update_profile" to "allow"),
        connection = ConnectionDto("active", listOf("read:user")),
        lastActivityAt = "2026-10-26T10:00:00Z",
    )

    @Test
    fun mapsAgentFields() {
        val a = agent().toDomain()!!
        assertEquals(ConnectorType.Github, a.connector)
        assertEquals(AgentStatus.Active, a.status)
        assertEquals(true, a.permissions.toggles["read_prs"])
        assertEquals(ApprovalMode.Allow, a.permissions.approvalModes["github.update_profile"])
        assertEquals(ConnectionStatus.Active, a.connection?.status)
        assertEquals(Instant.parse("2026-10-26T10:00:00Z"), a.lastActivityAt)
    }

    @Test
    fun connectorsOutsideTheThreeAreRejected() {
        assertNull(agent(connector = "todoist").toDomain())
        assertNull(agent(connector = "telegram").toDomain())
        assertNull(agent(connector = "custom").toDomain())
    }

    @Test
    fun unknownValuesFallBackToSafeDefaults() {
        assertEquals(AgentStatus.Paused, AgentStatus.fromWire("weird"))
        assertEquals(ApprovalMode.Ask, ApprovalMode.fromWire("weird"))
        assertEquals(ApprovalMode.Ask, ApprovalMode.fromWire(null))
        assertEquals(ConnectionStatus.NeedsReauth, ConnectionStatus.fromWire("weird"))
        assertEquals(ApprovalStatus.Expired, ApprovalStatus.fromWire("weird"))
    }

    @Test
    fun badTimestampBecomesNullNotACrash() {
        assertNull(agent().copy(lastActivityAt = "yesterday").toDomain()!!.lastActivityAt)
    }

    @Test
    fun messageBlocksAreParsedAndUnknownOnesDropped() {
        val dto = json.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m1","conversation_id":"c1","seq":3,"role":"assistant","created_at":"2026-10-26T10:00:00Z",
              "content":[
                {"type":"text","text":"Hello"},
                {"type":"tool_call","id":"t1","tool":"github.list_my_pull_requests","args":{"state":"open"}},
                {"type":"tool_result_summary","tool_call_id":"t1","summary":"Fetched 3 open PRs"},
                {"type":"hologram","x":1},
                {"type":"text"}
              ]}""",
        )
        val message = dto.toDomain()!!
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(3, message.blocks.size)
        assertTrue(message.blocks[0] is ContentBlock.Text)
        assertEquals("github.list_my_pull_requests", (message.blocks[1] as ContentBlock.ToolCall).tool)
        assertEquals("Fetched 3 open PRs", (message.blocks[2] as ContentBlock.ToolResultSummary).summary)
    }

    @Test
    fun unknownMessageRoleIsSkipped() {
        assertNull(MessageDto("m", "c", 1, "system").toDomain())
    }

    @Test
    fun approvalKeepsExactArguments() {
        val dto = json.decodeFromString(
            ApprovalDto.serializer(),
            """{"id":"p1","run_id":"r1","agent_id":"a1","tool":"gmail.send_email","status":"pending",
               "args":{"to":["x@example.com"],"subject":"Hi","body":"Body text"}}""",
        )
        val approval = dto.toDomain()
        assertEquals(ApprovalStatus.Pending, approval.status)
        assertEquals("Hi", (approval.args["subject"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun updateRequestOmitsUnchangedFields() {
        val body = json.encodeToString(
            UpdateAgentRequest.serializer(),
            dev.agenthub.app.core.model.AgentUpdate(name = "New").toRequest(),
        )
        assertEquals("""{"name":"New"}""", body)
    }

    @Test
    fun createRequestCarriesWireValuesForApprovalModes() {
        val request = dev.agenthub.app.core.model.NewAgent(
            templateId = "gmail-assistant", name = "Mail", instructions = "x",
            permissions = dev.agenthub.app.core.model.AgentPermissions(
                toggles = mapOf("read_email" to true),
                approvalModes = mapOf("gmail.create_draft" to ApprovalMode.Ask),
            ),
        ).toRequest()
        assertEquals("ask", request.approvalModes["gmail.create_draft"])
        assertNotNull(request.toggles["read_email"])
    }
}
