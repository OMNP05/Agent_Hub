package dev.agenthub.app.core.network.dto

import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentConnection
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.Approval
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.ConnectionStatus
import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.model.Conversation
import dev.agenthub.app.core.model.LogEntry
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.model.MessageRole
import dev.agenthub.app.core.model.NewAgent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun parseInstant(value: String?): Instant? =
    try { value?.let(Instant::parse) } catch (e: DateTimeParseException) { null }

/** Returns null for connectors outside the v1 catalog so the list can skip them. */
fun AgentDto.toDomain(): Agent? {
    val type = ConnectorType.fromWire(connector) ?: return null
    return Agent(
        id = id,
        name = name,
        templateId = templateId,
        connector = type,
        instructions = instructions,
        status = AgentStatus.fromWire(status),
        llmProfileId = llmProfileId,
        timeZone = timeZone,
        permissions = AgentPermissions(toggles, approvalModes.mapValues { ApprovalMode.fromWire(it.value) }),
        connection = connection?.let { AgentConnection(ConnectionStatus.fromWire(it.status), it.scopesGranted) },
        lastActivityAt = parseInstant(lastActivityAt),
    )
}

fun NewAgent.toRequest() = CreateAgentRequest(
    templateId = templateId,
    name = name,
    instructions = instructions,
    toggles = permissions.toggles,
    approvalModes = permissions.approvalModes.mapValues { it.value.wire },
    llmProfileId = llmProfileId,
    timeZone = timeZone,
)

fun AgentUpdate.toRequest() = UpdateAgentRequest(
    name = name,
    instructions = instructions,
    toggles = permissions?.toggles,
    approvalModes = permissions?.approvalModes?.mapValues { it.value.wire },
    llmProfileId = llmProfileId,
    timeZone = timeZone,
    status = status?.wire,
)

fun ConversationDto.toDomain() = Conversation(id, agentId, title, parseInstant(updatedAt) ?: Instant.EPOCH)

/** Returns null for unknown roles; unknown content blocks are dropped, not fatal. */
fun MessageDto.toDomain(): Message? {
    val messageRole = MessageRole.fromWire(role) ?: return null
    val blocks = content.mapNotNull { element ->
        val obj = (element as? JsonObject) ?: return@mapNotNull null
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { ContentBlock.Text(it) }
            "tool_call" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ContentBlock.ToolCall(id, tool, runCatching { obj["args"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
            }
            "tool_result_summary" -> {
                val id = obj["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ContentBlock.ToolResultSummary(id, obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
            else -> null
        }
    }
    return Message(id, conversationId, seq, messageRole, blocks, parseInstant(createdAt) ?: Instant.EPOCH)
}

fun ApprovalDto.toDomain() = Approval(
    id = id,
    runId = runId,
    agentId = agentId,
    agentName = agentName,
    tool = tool,
    args = args,
    status = ApprovalStatus.fromWire(status),
    createdAt = parseInstant(createdAt),
    expiresAt = parseInstant(expiresAt),
)

fun LogEntryDto.toDomain() = LogEntry(id, agentId, tool, argsRedacted, resultSummary, parseInstant(createdAt))
