package dev.agenthub.app.chat.ui

import dev.agenthub.app.core.error.DataError

/** One row in the chat list. Stable [key]s let LazyColumn keep scroll position while streaming. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem

    data class Assistant(override val key: String, val text: String, val isStreaming: Boolean) : ChatItem

    /** Small card such as "Fetched 3 open PRs". [summary] is null while the tool is still running. */
    data class Tool(
        override val key: String,
        val tool: String,
        val summary: String?,
        val ok: Boolean,
    ) : ChatItem {
        val isRunning: Boolean get() = summary == null
    }

    data class ApprovalPending(override val key: String, val approvalId: String, val tool: String) : ChatItem

    data class Failure(override val key: String, val error: DataError) : ChatItem
}
