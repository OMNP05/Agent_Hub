package dev.agenthub.app.agents.data

import app.cash.turbine.test
import dev.agenthub.app.core.database.FakeAgentDao
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.NewAgent
import dev.agenthub.app.core.network.UnconfinedDispatchers
import dev.agenthub.app.core.network.agentHubApi
import dev.agenthub.app.core.network.dto.toDomain
import dev.agenthub.app.core.network.testJson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var dao: FakeAgentDao
    private lateinit var repo: DefaultAgentRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dao = FakeAgentDao()
        repo = DefaultAgentRepository(server.agentHubApi(), dao, testJson, UnconfinedDispatchers)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun agentJson(id: String, connector: String = "github") =
        """{"id":"$id","name":"Agent $id","template_id":"t","connector":"$connector","status":"active"}"""

    @Test
    fun refreshCachesAgentsAndObserveEmitsThem() = runTest {
        server.enqueue(MockResponse().setBody("[${agentJson("a1")},${agentJson("a2", "gmail")}]"))

        assertEquals(AppResult.Success(Unit), repo.refresh())

        repo.observeAgents().test {
            assertEquals(listOf("a1", "a2"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshDropsUnsupportedConnectorsAndRemovesStaleRows() = runTest {
        server.enqueue(MockResponse().setBody("[${agentJson("a1")},${agentJson("a2")}]"))
        repo.refresh()
        server.enqueue(MockResponse().setBody("[${agentJson("a1")},${agentJson("x", "todoist")}]"))
        repo.refresh()

        repo.observeAgents().test {
            assertEquals(listOf("a1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedRefreshKeepsTheCacheAndReportsTheError() = runTest {
        server.enqueue(MockResponse().setBody("[${agentJson("a1")}]"))
        repo.refresh()
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>upstream stack trace</html>"))

        assertEquals(AppResult.Failure(DataError.Unknown), repo.refresh())
        assertEquals(1, dao.snapshot.size)
    }

    @Test
    fun unauthorizedMapsToUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AppResult.Failure(DataError.Unauthorized), repo.refresh())
    }

    @Test
    fun offlineMapsToNetworkError() = runTest {
        server.shutdown()
        assertEquals(AppResult.Failure(DataError.Network), repo.refresh())
    }

    @Test
    fun createSendsRequestAndCachesResult() = runTest {
        server.enqueue(MockResponse().setBody(agentJson("new")))

        val result = repo.create(
            NewAgent("github-assistant", "Mine", "instr", AgentPermissions(mapOf("read_prs" to true))),
        )

        assertTrue(result is AppResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"template_id\":\"github-assistant\""))
        assertEquals(1, dao.snapshot.size)
    }

    @Test
    fun createdAgentWithUnsupportedConnectorIsRejected() = runTest {
        server.enqueue(MockResponse().setBody(agentJson("new", "todoist")))
        val result = repo.create(NewAgent("t", "n", "i", AgentPermissions()))
        assertEquals(AppResult.Failure(DataError.Unknown), result)
        assertEquals(0, dao.snapshot.size)
    }

    @Test
    fun updatePatchesOnlyChangedFields() = runTest {
        server.enqueue(MockResponse().setBody(agentJson("a1")))
        repo.update("a1", AgentUpdate(name = "Renamed"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/agents/a1", request.path)
        assertEquals("""{"name":"Renamed"}""", request.body.readUtf8())
    }

    @Test
    fun deleteRemovesFromCacheOnlyOnSuccess() = runTest {
        server.enqueue(MockResponse().setBody("[${agentJson("a1")}]"))
        repo.refresh()

        server.enqueue(MockResponse().setResponseCode(500))
        repo.delete("a1")
        assertEquals(1, dao.snapshot.size)

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AppResult.Success(Unit), repo.delete("a1"))
        assertEquals(0, dao.snapshot.size)
    }

    @Test
    fun startConnectReturnsOnlyHttpsUrls() = runTest {
        val agent = dev.agenthub.app.core.network.dto.AgentDto(
            id = "a1", name = "n", templateId = "t", connector = "gmail",
        ).toDomain()!!

        server.enqueue(MockResponse().setBody("""{"auth_url":"https://accounts.example/consent?state=s"}"""))
        assertEquals(AppResult.Success("https://accounts.example/consent?state=s"), repo.startConnect(agent))
        val request = server.takeRequest()
        assertEquals("/oauth/gmail/start", request.requestUrl!!.encodedPath)
        assertEquals("a1", request.requestUrl!!.queryParameter("agent_id"))

        server.enqueue(MockResponse().setBody("""{"auth_url":"http://insecure.example/consent"}"""))
        assertEquals(AppResult.Failure(DataError.Unknown), repo.startConnect(agent))

        server.enqueue(MockResponse().setBody("""{"auth_url":"intent://evil#Intent;end"}"""))
        assertEquals(AppResult.Failure(DataError.Unknown), repo.startConnect(agent))
    }

    @Test
    fun backendErrorCodeIsMapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"connection_needs_reauth"}}"""))
        assertEquals(AppResult.Failure(DataError.ConnectionNeedsReauth), repo.update("a1", AgentUpdate(name = "x")))
    }
}
