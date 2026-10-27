package dev.agenthub.app.approvals

import dev.agenthub.app.approvals.data.ApprovalRepository
import dev.agenthub.app.approvals.data.Decision
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Approval
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.LogEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun approval(
    id: String = "p1",
    tool: String = "github.update_profile",
    status: ApprovalStatus = ApprovalStatus.Pending,
    args: Map<String, String> = mapOf("bio" to "Android dev"),
) = Approval(id, "r1", "a1", "Work", tool, JsonObject(args.mapValues { JsonPrimitive(it.value) }), status, null, null)

fun logEntry(id: String, at: String = "2026-10-26T10:00:00Z") =
    LogEntry(id, "a1", "github.get_profile", null, "ok $id", java.time.Instant.parse(at))

class FakeApprovalRepository : ApprovalRepository {
    var pendingResult: AppResult<List<Approval>> = AppResult.Success(emptyList())
    var decideResult: AppResult<Approval>? = null
    var logPages: MutableList<AppResult<List<LogEntry>>> = mutableListOf()

    /** When set, [decide] waits for it, so tests can tap again while the first request is in flight. */
    var gate: CompletableDeferred<Unit>? = null

    val decisions = mutableListOf<Pair<String, Decision>>()
    val logRequests = mutableListOf<Triple<String, String?, Int>>()
    var pendingCalls = 0

    override suspend fun pending(): AppResult<List<Approval>> { pendingCalls++; return pendingResult }

    override suspend fun decide(id: String, decision: Decision): AppResult<Approval> {
        decisions += id to decision
        gate?.await()
        return decideResult ?: AppResult.Success(
            approval(id = id, status = if (decision == Decision.Approve) ApprovalStatus.Approved else ApprovalStatus.Rejected),
        )
    }

    override suspend fun logs(agentId: String, before: String?, limit: Int): AppResult<List<LogEntry>> {
        logRequests += Triple(agentId, before, limit)
        return if (logPages.isEmpty()) AppResult.Success(emptyList()) else logPages.removeAt(0)
    }
}

val expired = AppResult.Failure(DataError.ApprovalExpired)
