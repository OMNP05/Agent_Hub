package dev.agenthub.app.chat.data

import app.cash.turbine.test
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.RunEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var stream: OkHttpChatStream

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        stream = OkHttpChatStream(OkHttpClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sse(vararg messages: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(messages.joinToString("") { "$it\n\n" })

    @Test
    fun sendPostsTheMessageAndStreamsEventsInOrderWithIds() = runTest {
        server.enqueue(
            sse(
                "id: 1\nevent: run.started\ndata: {\"run_id\":\"r1\",\"conversation_id\":\"c1\"}",
                "id: 2\nevent: message.delta\ndata: {\"text\":\"Hi\"}",
                "id: 3\nevent: run.completed\ndata: {\"message_id\":\"m1\"}",
            ),
        )

        stream.send("a1", "c1", "Hello").test {
            assertEquals(StreamEvent("1", RunEvent.Started("r1", "c1")), awaitItem())
            assertEquals(StreamEvent("2", RunEvent.Delta("Hi")), awaitItem())
            assertEquals(StreamEvent("3", RunEvent.Completed("m1")), awaitItem())
            awaitComplete()
        }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/agents/a1/chat", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"conversation_id\":\"c1\"") && body.contains("\"message\":\"Hello\""))
    }

    @Test
    fun unknownEventsAreSkipped() = runTest {
        server.enqueue(
            sse(
                "id: 1\nevent: run.hologram\ndata: {}",
                "id: 2\nevent: run.completed\ndata: {}",
            ),
        )
        stream.send("a1", "c1", "x").test {
            assertEquals(StreamEvent("2", RunEvent.Completed(null)), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun resumeSendsTheLastEventIdHeader() = runTest {
        server.enqueue(sse("id: 5\nevent: run.completed\ndata: {}"))
        stream.resume("r1", "4").test {
            awaitItem()
            awaitComplete()
        }
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/runs/r1/events", request.path)
        assertEquals("4", request.getHeader("Last-Event-ID"))
    }

    @Test
    fun busyResponseBecomesAMappedErrorWithoutRawBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"agent_busy"},"trace":"secret stack"}"""))
        stream.send("a1", "c1", "x").test {
            val error = awaitError()
            assertEquals(DataError.AgentBusy, (error as StreamException).error)
            assertTrue(!(error.message ?: "").contains("secret"))
        }
    }

    @Test
    fun unauthorizedResponseIsMapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        stream.send("a1", "c1", "x").test {
            assertEquals(DataError.Unauthorized, (awaitError() as StreamException).error)
        }
    }

    @Test
    fun connectionFailureIsANetworkError() = runTest {
        server.shutdown()
        stream.send("a1", "c1", "x").test {
            assertEquals(DataError.Network, (awaitError() as StreamException).error)
        }
    }
}
