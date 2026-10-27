package dev.agenthub.app.chat.data

import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.core.network.ApiErrorParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class StreamEvent(val id: String?, val event: RunEvent)

/** The stream failed before a terminal event. [error] is already mapped; no raw text is carried. */
class StreamException(val error: DataError) : Exception(error.toString())

interface ChatStream {
    /** Sends a message and streams the run. Collect to start; cancelling the collector closes the connection. */
    fun send(agentId: String, conversationId: String, message: String): Flow<StreamEvent>

    /** Re-attaches to a run after a dropped connection, continuing after [lastEventId]. */
    fun resume(runId: String, lastEventId: String?): Flow<StreamEvent>
}

class OkHttpChatStream(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
) : ChatStream {

    override fun send(agentId: String, conversationId: String, message: String): Flow<StreamEvent> {
        val body = buildJsonObject {
            put("conversation_id", conversationId)
            put("message", message)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("agents/$agentId/chat").build())
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        return open(request)
    }

    override fun resume(runId: String, lastEventId: String?): Flow<StreamEvent> {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("runs/$runId/events").build())
            .header("Accept", "text/event-stream")
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .get()
            .build()
        return open(request)
    }

    private fun open(request: Request): Flow<StreamEvent> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = RunEventParser.parse(type, data)
                if (event == RunEvent.Unknown) return
                trySend(StreamEvent(id, event))
                if (event.isTerminal) close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response != null && !response.isSuccessful ->
                        ApiErrorParser.toError(response.code, runCatching { response.body.string() }.getOrNull())
                    else -> DataError.Network
                }
                close(StreamException(error))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
