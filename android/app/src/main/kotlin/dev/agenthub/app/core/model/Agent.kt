package dev.agenthub.app.core.model

import java.time.Instant

/** v1 supports exactly these three connectors (CLAUDE.md). Anything else from the backend is ignored. */
enum class ConnectorType(val wire: String) {
    Github("github"),
    Gmail("gmail"),
    Calendar("calendar");

    companion object {
        fun fromWire(value: String?): ConnectorType? = entries.firstOrNull { it.wire == value }
    }
}

enum class AgentStatus(val wire: String) {
    Draft("draft"),
    Active("active"),
    Paused("paused");

    companion object {
        // An unrecognised status is shown as Paused so the UI never claims an agent is running when unsure.
        fun fromWire(value: String?): AgentStatus = entries.firstOrNull { it.wire == value } ?: Paused
    }
}

enum class ConnectionStatus(val wire: String) {
    Active("active"),
    NeedsReauth("needs_reauth"),
    Revoked("revoked");

    companion object {
        fun fromWire(value: String?): ConnectionStatus = entries.firstOrNull { it.wire == value } ?: NeedsReauth
    }
}

enum class ApprovalMode(val wire: String) {
    Ask("ask"),
    Allow("allow");

    companion object {
        // Unknown means ask: the safe default for anything we cannot interpret.
        fun fromWire(value: String?): ApprovalMode = entries.firstOrNull { it.wire == value } ?: Ask
    }
}

/** What the user switched on for this agent. The backend Gateway is the real enforcement. */
data class AgentPermissions(
    val toggles: Map<String, Boolean> = emptyMap(),
    val approvalModes: Map<String, ApprovalMode> = emptyMap(),
)

/** Connection state only. Provider tokens never reach the app. */
data class AgentConnection(val status: ConnectionStatus, val scopesGranted: List<String>)

data class Agent(
    val id: String,
    val name: String,
    val templateId: String,
    val connector: ConnectorType,
    val instructions: String,
    val status: AgentStatus,
    val llmProfileId: String?,
    val timeZone: String?,
    val permissions: AgentPermissions,
    val connection: AgentConnection?,
    val lastActivityAt: Instant?,
)

data class NewAgent(
    val templateId: String,
    val name: String,
    val instructions: String,
    val permissions: AgentPermissions,
    val llmProfileId: String? = null,
    val timeZone: String? = null,
)

/** Null fields are left unchanged. */
data class AgentUpdate(
    val name: String? = null,
    val instructions: String? = null,
    val permissions: AgentPermissions? = null,
    val llmProfileId: String? = null,
    val timeZone: String? = null,
    val status: AgentStatus? = null,
)
