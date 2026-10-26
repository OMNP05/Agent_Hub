package dev.agenthub.app.core.model

import kotlinx.serialization.json.JsonObject
import java.time.Instant

enum class ApprovalStatus(val wire: String) {
    Pending("pending"),
    Approved("approved"),
    Rejected("rejected"),
    Expired("expired");

    companion object {
        // Unknown is treated as Expired so an unreadable approval can never look approvable.
        fun fromWire(value: String?): ApprovalStatus = entries.firstOrNull { it.wire == value } ?: Expired
    }
}

/** [args] are the exact stored arguments the user is approving; they are shown in full on the card. */
data class Approval(
    val id: String,
    val runId: String,
    val agentId: String,
    val agentName: String?,
    val tool: String,
    val args: JsonObject,
    val status: ApprovalStatus,
    val createdAt: Instant?,
    val expiresAt: Instant?,
)

data class LogEntry(
    val id: String,
    val agentId: String,
    val tool: String,
    val argsRedacted: JsonObject?,
    val resultSummary: String,
    val createdAt: Instant?,
)
