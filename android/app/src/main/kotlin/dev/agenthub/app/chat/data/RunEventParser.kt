package dev.agenthub.app.chat.data

import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns one SSE message into a [RunEvent]. Malformed or unknown messages become [RunEvent.Unknown], never a crash. */
object RunEventParser {
    fun parse(type: String?, data: String): RunEvent {
        val obj = try {
            Json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            return RunEvent.Unknown
        }
        return when (type) {
            "run.started" -> obj.str("run_id")?.let { RunEvent.Started(it, obj.str("conversation_id")) } ?: RunEvent.Unknown
            "message.delta" -> obj.str("text")?.let(RunEvent::Delta) ?: RunEvent.Unknown
            "tool.started" -> {
                val id = obj.str("tool_call_id")
                val tool = obj.str("tool")
                if (id != null && tool != null) RunEvent.ToolStarted(id, tool) else RunEvent.Unknown
            }
            "tool.finished" -> {
                val id = obj.str("tool_call_id")
                val tool = obj.str("tool")
                if (id != null && tool != null) {
                    RunEvent.ToolFinished(id, tool, obj.str("summary").orEmpty(), obj["ok"]?.jsonPrimitive?.booleanOrNull ?: true)
                } else {
                    RunEvent.Unknown
                }
            }
            "approval.required" -> {
                val id = obj.str("approval_id")
                if (id != null) RunEvent.ApprovalRequired(id, obj.str("tool").orEmpty()) else RunEvent.Unknown
            }
            "run.completed" -> RunEvent.Completed(obj.str("message_id"))
            "run.error" -> RunEvent.Failed(DataError.fromCode(obj.str("code")))
            else -> RunEvent.Unknown
        }
    }

    private fun JsonObject.str(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (e: IllegalArgumentException) {
        null
    }
}
