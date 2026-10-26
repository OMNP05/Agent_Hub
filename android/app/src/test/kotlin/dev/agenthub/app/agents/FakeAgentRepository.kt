package dev.agenthub.app.agents

import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentConnection
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.ConnectionStatus
import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.NewAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

fun testAgent(
    id: String = "a1",
    name: String = "Work",
    templateId: String = "gmail-assistant",
    connector: ConnectorType = ConnectorType.Gmail,
    status: AgentStatus = AgentStatus.Active,
    connection: AgentConnection? = AgentConnection(ConnectionStatus.Active, listOf("gmail.readonly")),
    permissions: AgentPermissions = AgentPermissions(mapOf("read_email" to true)),
) = Agent(id, name, templateId, connector, "instr", status, null, null, permissions, connection, null)

class FakeAgentRepository(initial: List<Agent> = emptyList()) : AgentRepository {
    val agents = MutableStateFlow(initial)

    var refreshResult: AppResult<Unit> = AppResult.Success(Unit)
    var createResult: AppResult<Agent>? = null
    var updateResult: AppResult<Agent>? = null
    var connectResult: AppResult<String> = AppResult.Success("https://provider.example/consent")
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)

    var refreshCount = 0
    var lastCreated: NewAgent? = null
    var lastUpdate: AgentUpdate? = null
    var connectCalls = 0
    var deleted: String? = null

    override fun observeAgents(): Flow<List<Agent>> = agents

    override suspend fun refresh(): AppResult<Unit> { refreshCount++; return refreshResult }

    override suspend fun create(agent: NewAgent): AppResult<Agent> {
        lastCreated = agent
        return createResult ?: AppResult.Success(testAgent(id = "new", name = agent.name, permissions = agent.permissions))
    }

    override suspend fun update(id: String, update: AgentUpdate): AppResult<Agent> {
        lastUpdate = update
        val current = agents.value.first { it.id == id }
        val result = updateResult ?: AppResult.Success(
            current.copy(
                name = update.name ?: current.name,
                instructions = update.instructions ?: current.instructions,
                permissions = update.permissions ?: current.permissions,
                status = update.status ?: current.status,
            ),
        )
        if (result is AppResult.Success) agents.value = agents.value.map { if (it.id == id) result.value else it }
        return result
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        deleted = id
        if (deleteResult is AppResult.Success) agents.value = agents.value.filterNot { it.id == id }
        return deleteResult
    }

    override suspend fun startConnect(agent: Agent): AppResult<String> { connectCalls++; return connectResult }
    override suspend fun disconnect(agentId: String): AppResult<Unit> = AppResult.Success(Unit)
}

val networkFailure = AppResult.Failure(DataError.Network)
