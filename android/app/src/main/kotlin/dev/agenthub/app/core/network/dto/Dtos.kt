package dev.agenthub.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConnectionDto(
    val status: String,
    @SerialName("scopes_granted") val scopesGranted: List<String> = emptyList(),
)

@Serializable
data class AgentDto(
    val id: String,
    val name: String,
    @SerialName("template_id") val templateId: String,
    val connector: String,
    val instructions: String = "",
    val status: String = "draft",
    @SerialName("llm_profile_id") val llmProfileId: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    val toggles: Map<String, Boolean> = emptyMap(),
    @SerialName("approval_modes") val approvalModes: Map<String, String> = emptyMap(),
    val connection: ConnectionDto? = null,
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
)

@Serializable
data class CreateAgentRequest(
    @SerialName("template_id") val templateId: String,
    val name: String,
    val instructions: String,
    val toggles: Map<String, Boolean>,
    @SerialName("approval_modes") val approvalModes: Map<String, String>,
    @SerialName("llm_profile_id") val llmProfileId: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
)

/** Null fields are omitted from the JSON (explicitNulls = false), meaning "leave unchanged". */
@Serializable
data class UpdateAgentRequest(
    val name: String? = null,
    val instructions: String? = null,
    val toggles: Map<String, Boolean>? = null,
    @SerialName("approval_modes") val approvalModes: Map<String, String>? = null,
    @SerialName("llm_profile_id") val llmProfileId: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    val status: String? = null,
)

@Serializable
data class DeviceRequest(@SerialName("fcm_token") val fcmToken: String)

@Serializable
data class OAuthStartDto(@SerialName("auth_url") val authUrl: String)

@Serializable
data class ConversationDto(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** [content] stays a raw array so one unknown block type cannot break a whole message list. */
@Serializable
data class MessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val seq: Long,
    val role: String,
    val content: JsonArray = JsonArray(emptyList()),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class RunDto(
    val id: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class ApprovalDto(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("agent_name") val agentName: String? = null,
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class DecisionRequest(val decision: String)

@Serializable
data class LogEntryDto(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val tool: String,
    @SerialName("args_redacted") val argsRedacted: JsonObject? = null,
    @SerialName("result_summary") val resultSummary: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)
