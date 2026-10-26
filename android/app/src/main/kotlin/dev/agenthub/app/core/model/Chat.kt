package dev.agenthub.app.core.model

import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class Conversation(
    val id: String,
    val agentId: String,
    val title: String?,
    val updatedAt: Instant,
)

enum class MessageRole(val wire: String) {
    User("user"),
    Assistant("assistant"),
    Tool("tool");

    companion object {
        fun fromWire(value: String?): MessageRole? = entries.firstOrNull { it.wire == value }
    }
}

/** Stored history keeps trimmed tool summaries only, never raw provider data (architecture 3.7). */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class ToolCall(val id: String, val tool: String, val args: JsonObject) : ContentBlock
    data class ToolResultSummary(val toolCallId: String, val summary: String) : ContentBlock
}

data class Message(
    val id: String,
    val conversationId: String,
    val seq: Long,
    val role: MessageRole,
    val blocks: List<ContentBlock>,
    val createdAt: Instant,
)
