package dev.agenthub.app.approvals.data

import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.network.UnconfinedDispatchers
import dev.agenthub.app.core.network.agentHubApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ApprovalRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: DefaultApprovalRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = DefaultApprovalRepository(server.agentHubApi(), UnconfinedDispatchers)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun approval(status: String) =
        """{"id":"p1","run_id":"r1","agent_id":"a1","tool":"gmail.send_email","status":"$status","args":{"subject":"Hi"}}"""

    @Test
    fun decisionIsPostedWithOnlyTheDecisionValue() = runTest {
        server.enqueue(MockResponse().setBody(approval("approved")))

        val result = repo.decide("p1", Decision.Approve) as AppResult.Success

        assertEquals(ApprovalStatus.Approved, result.value.status)
        val request = server.takeRequest()
        assertEquals("/approvals/p1/decision", request.path)
        assertEquals("""{"decision":"approve"}""", request.body.readUtf8())
    }

    @Test
    fun alreadyDecidedApprovalSurfacesAsAnErrorNotASecondExecution() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"approval_expired"}}"""))
        assertEquals(AppResult.Failure(DataError.ApprovalExpired), repo.decide("p1", Decision.Approve))
    }

    @Test
    fun pendingListIsMapped() = runTest {
        server.enqueue(MockResponse().setBody("[${approval("pending")}]"))
        val list = (repo.pending() as AppResult.Success).value
        assertEquals(ApprovalStatus.Pending, list.single().status)
    }

    @Test
    fun logsRequestIncludesPagingParameters() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        repo.logs("a1", before = "2026-10-26T00:00:00Z", limit = 25)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/agents/a1/logs", url.encodedPath)
        assertEquals("25", url.queryParameter("limit"))
        assertEquals("2026-10-26T00:00:00Z", url.queryParameter("before"))
    }
}
