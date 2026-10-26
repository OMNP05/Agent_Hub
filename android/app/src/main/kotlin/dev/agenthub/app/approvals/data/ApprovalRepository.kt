package dev.agenthub.app.approvals.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.map
import dev.agenthub.app.core.model.Approval
import dev.agenthub.app.core.model.LogEntry
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiCall
import dev.agenthub.app.core.network.dto.DecisionRequest
import dev.agenthub.app.core.network.dto.toDomain
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class Decision(val wire: String) { Approve("approve"), Reject("reject") }

interface ApprovalRepository {
    suspend fun pending(): AppResult<List<Approval>>

    /**
     * Sends the user's tap. Only called from the approval card UI; voice code must not depend on this
     * (CLAUDE.md rule 3). The backend applies it atomically and runs the stored arguments only.
     */
    suspend fun decide(id: String, decision: Decision): AppResult<Approval>
    suspend fun logs(agentId: String, before: String? = null, limit: Int = 50): AppResult<List<LogEntry>>
}

@Singleton
class DefaultApprovalRepository @Inject constructor(
    private val api: AgentHubApi,
    private val dispatchers: DispatcherProvider,
) : ApprovalRepository {

    override suspend fun pending(): AppResult<List<Approval>> = withContext(dispatchers.io) {
        apiCall { api.approvals() }.map { list -> list.map { it.toDomain() } }
    }

    override suspend fun decide(id: String, decision: Decision): AppResult<Approval> = withContext(dispatchers.io) {
        apiCall { api.decide(id, DecisionRequest(decision.wire)) }.map { it.toDomain() }
    }

    override suspend fun logs(agentId: String, before: String?, limit: Int): AppResult<List<LogEntry>> =
        withContext(dispatchers.io) {
            apiCall { api.logs(agentId, before, limit) }.map { list -> list.map { it.toDomain() } }
        }
}
