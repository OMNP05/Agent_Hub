package dev.agenthub.app.llm.data

import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.network.UnconfinedDispatchers
import dev.agenthub.app.core.network.agentHubApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: DefaultLlmRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = DefaultLlmRepository(server.agentHubApi(), UnconfinedDispatchers)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun profileJson(id: String = "l1", provider: String = "anthropic") =
        """{"id":"$id","name":"My Claude","provider":"$provider","model":"claude-x","temperature":0.2,"max_tokens":1000,"key_last4":"3456","is_default":true}"""

    private fun input(apiKey: String = "sk-live-abcdef123456") = LlmProfileInput(
        "My Claude", LlmProvider.Anthropic, "", "claude-x", 0.2, 1000, apiKey, makeDefault = true,
    )

    @Test
    fun listsProfilesAndOnlyEverReceivesTheLastFourOfTheKey() = runTest {
        server.enqueue(MockResponse().setBody("[${profileJson()}]"))
        val profile = (repo.profiles() as AppResult.Success).value.single()
        assertEquals("3456", profile.keyLast4)
        assertTrue(profile.isDefault)
    }

    @Test
    fun profilesFromAnUnknownProviderAreSkipped() = runTest {
        server.enqueue(MockResponse().setBody("[${profileJson("a")},${profileJson("b", provider = "gemini")}]"))
        assertEquals(listOf("a"), (repo.profiles() as AppResult.Success).value.map { it.id })
    }

    @Test
    fun createSendsTheKeyOnceAndUsesWireNames() = runTest {
        server.enqueue(MockResponse().setBody(profileJson()))
        repo.create(input())
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/llm-profiles", request.path)
        assertTrue(body.contains("\"api_key\":\"sk-live-abcdef123456\""))
        assertTrue(body.contains("\"provider\":\"anthropic\"") && body.contains("\"max_tokens\":1000") && body.contains("\"is_default\":true"))
        assertFalse("an empty base url is omitted, not sent as an empty string", body.contains("base_url"))
    }

    @Test
    fun editingWithABlankKeyDoesNotSendOneSoTheStoredKeyIsKept() = runTest {
        server.enqueue(MockResponse().setBody(profileJson()))
        repo.update("l1", input(apiKey = ""))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/llm-profiles/l1", request.path)
        assertFalse(request.body.readUtf8().contains("api_key"))
    }

    @Test
    fun theResponseToASaveNeverContainsTheKeyInTheDomainModel() = runTest {
        server.enqueue(MockResponse().setBody(profileJson()))
        val saved = (repo.create(input("sk-live-abcdef123456")) as AppResult.Success).value
        assertFalse(saved.toString().contains("abcdef"))
        assertEquals("3456", saved.keyLast4)
    }

    @Test
    fun testingADraftSendsTheKeyToTheDraftEndpoint() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val result = repo.testDraft(input())
        assertEquals(AppResult.Success(TestResult(true)), result)
        assertEquals("/llm-profiles/test", server.takeRequest().path)
    }

    @Test
    fun testingASavedProfileUsesItsIdAndNoKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false}"""))
        assertEquals(AppResult.Success(TestResult(false)), repo.test("l1"))
        val request = server.takeRequest()
        assertEquals("/llm-profiles/l1/test", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun invalidKeyIsMappedFromTheBackendCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"llm_key_invalid"}}"""))
        assertEquals(AppResult.Failure(DataError.LlmKeyInvalid), repo.create(input()))
    }

    @Test
    fun deleteCallsTheProfileEndpoint() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AppResult.Success(Unit), repo.delete("l1"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/llm-profiles/l1", request.path)
    }

    @Test
    fun offlineIsANetworkError() = runTest {
        server.shutdown()
        assertEquals(AppResult.Failure(DataError.Network), repo.profiles())
    }
}
