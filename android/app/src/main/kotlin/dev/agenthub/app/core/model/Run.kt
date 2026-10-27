package dev.agenthub.app.core.model

import dev.agenthub.app.core.error.DataError

/** One event of a run's SSE stream (architecture 3.10). */
sealed interface RunEvent {
    data class Started(val runId: String, val conversationId: String?) : RunEvent
    data class Delta(val text: String) : RunEvent
    data class ToolStarted(val toolCallId: String, val tool: String) : RunEvent
    data class ToolFinished(val toolCallId: String, val tool: String, val summary: String, val ok: Boolean) : RunEvent
    data class ApprovalRequired(val approvalId: String, val tool: String) : RunEvent
    data class Completed(val messageId: String?) : RunEvent
    data class Failed(val error: DataError) : RunEvent

    /** An event type this app version does not know; ignored so newer backends keep working. */
    data object Unknown : RunEvent

    /** After these the server ends the stream. */
    val isTerminal: Boolean get() = this is Completed || this is Failed || this is ApprovalRequired
}

enum class RunStatus(val wire: String) {
    Queued("queued"),
    Running("running"),
    AwaitingApproval("awaiting_approval"),
    Completed("completed"),
    Failed("failed"),
    Cancelled("cancelled"),
    Expired("expired");

    val isActive: Boolean get() = this == Queued || this == Running

    companion object {
        fun fromWire(value: String?): RunStatus? = entries.firstOrNull { it.wire == value }
    }
}

data class RunState(val id: String, val status: RunStatus, val errorCode: DataError?)
