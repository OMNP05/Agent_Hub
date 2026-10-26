package dev.agenthub.app.chat.data

import app.cash.turbine.test
import dev.agenthub.app.core.database.FakeConversationDao
import dev.agenthub.app.core.database.FakeMessageDao
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.model.ContentBlock
import dev.agenthub.app.core.network.UnconfinedDispatchers
import dev.agenthub.app.core.network.agentHubApi
import dev.agenthub.app.core.network.testJson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var messages: FakeMessageDao
    private lateinit var repo: DefaultChatRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        messages = FakeMessageDao().apply { conversationToAgent = mapOf("c1" to "a1") }
        repo = DefaultChatRepository(server.agentHubApi(), FakeConversationDao(), messages, testJson, UnconfinedDispatchers)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun msg(id: String, seq: Int, text: String) =
        """{"id":"$id","conversation_id":"c1","seq":$seq,"role":"user","content":[{"type":"text","text":"$text"}]}"""

    @Test
    fun firstFetchAsksForEverythingThenOnlyNewerMessages() = runTest {
        server.enqueue(MockResponse().setBody("[${msg("m1", 1, "a")},${msg("m2", 2, "b")}]"))
        repo.refreshMessages("c1")
        assertNull(server.takeRequest().requestUrl?.queryParameter("after_seq"))

        server.enqueue(MockResponse().setBody("[${msg("m3", 3, "c")}]"))
        repo.refreshMessages("c1")
        assertEquals("2", server.takeRequest().requestUrl?.queryParameter("after_seq"))

        repo.observeMessages("c1").test {
            val list = awaitItem()
            assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
            assertEquals("c", (list.last().blocks.single() as ContentBlock.Text).text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun newConversationIsCachedPerAgent() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"c9","agent_id":"a1","updated_at":"2026-10-26T10:00:00Z"}"""))
        val result = repo.newConversation("a1") as AppResult.Success
        assertEquals("c9", result.value.id)

        repo.observeConversations("a1").test {
            assertEquals(listOf("c9"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeConversations("other").test {
            assertEquals(0, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteConversationClearsItsMessagesLocallyOnSuccess() = runTest {
        server.enqueue(MockResponse().setBody("[${msg("m1", 1, "a")}]"))
        repo.refreshMessages("c1")

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AppResult.Success(Unit), repo.deleteConversation("c1"))

        repo.observeMessages("c1").test {
            assertEquals(0, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedDeleteKeepsLocalMessages() = runTest {
        server.enqueue(MockResponse().setBody("[${msg("m1", 1, "a")}]"))
        repo.refreshMessages("c1")

        server.enqueue(MockResponse().setResponseCode(500))
        repo.deleteConversation("c1")

        repo.observeMessages("c1").test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
