package dev.agenthub.app.chat.ui

import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.model.MessageRole
import dev.agenthub.app.core.model.RunEvent

/**
 * Pure functions that build the chat list. Kept free of Android and coroutines so every stream
 * event can be unit tested.
 */
object ChatReducer {
    private const val LIVE_ASSISTANT_KEY = "live-assistant"

    /** Rows for the stored history. Tool calls and their summaries are folded into one tool card each. */
    fun fromHistory(messages: List<Message>): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        val toolIndexById = mutableMapOf<String, Int>()
        for (message in messages.sortedBy { it.seq }) {
            for ((i, block) in message.blocks.withIndex()) {
                val key = "m${message.id}-$i"
                when (block) {
                    is ContentBlock.Text -> when (message.role) {
                        MessageRole.User -> items += ChatItem.User(key, block.text)
                        MessageRole.Assistant -> items += ChatItem.Assistant(key, block.text, isStreaming = false)
                        MessageRole.Tool -> Unit
                    }
                    is ContentBlock.ToolCall -> {
                        toolIndexById[block.id] = items.size
                        items += ChatItem.Tool(key, block.tool, summary = null, ok = true)
                    }
                    is ContentBlock.ToolResultSummary -> {
                        val index = toolIndexById[block.toolCallId]
                        val existing = index?.let { items[it] as? ChatItem.Tool }
                        if (index != null && existing != null) {
                            items[index] = existing.copy(summary = block.summary)
                        } else {
                            items += ChatItem.Tool(key, tool = "", summary = block.summary, ok = true)
                        }
                    }
                }
            }
        }
        // A stored tool call with no result will never get one, so it must not look like it is still running.
        return items.map { if (it is ChatItem.Tool && it.summary == null) it.copy(summary = "") else it }
    }

    /** Applies one live event to the rows currently shown. */
    fun apply(items: List<ChatItem>, event: RunEvent): List<ChatItem> = when (event) {
        is RunEvent.Started, RunEvent.Unknown -> items

        is RunEvent.Delta -> {
            val last = items.lastOrNull()
            if (last is ChatItem.Assistant && last.isStreaming) {
                items.dropLast(1) + last.copy(text = last.text + event.text)
            } else {
                items + ChatItem.Assistant(LIVE_ASSISTANT_KEY + "-" + items.size, event.text, isStreaming = true)
            }
        }

        is RunEvent.ToolStarted -> finishStreaming(items) + ChatItem.Tool(toolKey(event.toolCallId), event.tool, null, ok = true)

        is RunEvent.ToolFinished -> {
            val key = toolKey(event.toolCallId)
            val finished = ChatItem.Tool(key, event.tool, event.summary, event.ok)
            if (items.any { it.key == key }) {
                items.map { if (it.key == key) finished else it }
            } else {
                finishStreaming(items) + finished
            }
        }

        is RunEvent.ApprovalRequired ->
            finishStreaming(items) + ChatItem.ApprovalPending("approval-${event.approvalId}", event.approvalId, event.tool)

        is RunEvent.Completed -> finishStreaming(items)

        is RunEvent.Failed -> finishStreaming(items) + ChatItem.Failure("failure-${items.size}", event.error)
    }

    fun withUserMessage(items: List<ChatItem>, localId: String, text: String): List<ChatItem> =
        items + ChatItem.User("local-$localId", text)

    /** Marks any tool card that never finished as stopped, used after cancel or a lost connection. */
    fun settle(items: List<ChatItem>): List<ChatItem> = finishStreaming(items).map {
        if (it is ChatItem.Tool && it.summary == null) it.copy(summary = "", ok = false) else it
    }

    private fun finishStreaming(items: List<ChatItem>): List<ChatItem> =
        items.map { if (it is ChatItem.Assistant && it.isStreaming) it.copy(isStreaming = false) else it }

    private fun toolKey(toolCallId: String) = "tool-$toolCallId"
}
