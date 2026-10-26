package dev.agenthub.app.agents.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.database.AgentDao
import dev.agenthub.app.core.database.AgentEntity
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.NewAgent
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiCall
import dev.agenthub.app.core.network.apiUnitCall
import dev.agenthub.app.core.network.dto.AgentDto
import dev.agenthub.app.core.network.dto.toDomain
import dev.agenthub.app.core.network.dto.toRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface AgentRepository {
    /** Cached agents, newest first. Emits again whenever [refresh] or a mutation changes the cache. */
    fun observeAgents(): Flow<List<Agent>>
    suspend fun refresh(): AppResult<Unit>
    suspend fun create(agent: NewAgent): AppResult<Agent>
    suspend fun update(id: String, update: AgentUpdate): AppResult<Agent>
    suspend fun delete(id: String): AppResult<Unit>

    /** Asks the backend for the provider consent URL for this agent. Only https URLs are returned. */
    suspend fun startConnect(agent: Agent): AppResult<String>
    suspend fun disconnect(agentId: String): AppResult<Unit>
}

@Singleton
class DefaultAgentRepository @Inject constructor(
    private val api: AgentHubApi,
    private val dao: AgentDao,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : AgentRepository {

    override fun observeAgents(): Flow<List<Agent>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            // A row that no longer decodes (older app version) is skipped; the next refresh rewrites it.
            runCatching { json.decodeFromString(AgentDto.serializer(), row.json).toDomain() }.getOrNull()
        }
    }

    override suspend fun refresh(): AppResult<Unit> = withContext(dispatchers.io) {
        when (val result = apiCall { api.agents() }) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                // Unsupported connectors are dropped here, so the catalog stays limited to the three agents.
                val supported = result.value.filter { it.toDomain() != null }
                dao.replaceAll(supported.mapIndexed { index, dto -> dto.toEntity(sortKey = (supported.size - index).toLong()) })
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun create(agent: NewAgent): AppResult<Agent> = withContext(dispatchers.io) {
        saveAndMap(apiCall { api.createAgent(agent.toRequest()) })
    }

    override suspend fun update(id: String, update: AgentUpdate): AppResult<Agent> = withContext(dispatchers.io) {
        saveAndMap(apiCall { api.updateAgent(id, update.toRequest()) })
    }

    override suspend fun delete(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        val result = apiUnitCall { api.deleteAgent(id) }
        if (result is AppResult.Success) dao.delete(id)
        result
    }

    override suspend fun startConnect(agent: Agent): AppResult<String> = withContext(dispatchers.io) {
        when (val result = apiCall { api.oauthStart(agent.connector.wire, agent.id) }) {
            is AppResult.Failure -> result
            is AppResult.Success ->
                // Never open anything but https in the browser, whatever the server returns.
                if (result.value.authUrl.startsWith("https://")) {
                    AppResult.Success(result.value.authUrl)
                } else {
                    AppResult.Failure(dev.agenthub.app.core.error.DataError.Unknown)
                }
        }
    }

    override suspend fun disconnect(agentId: String): AppResult<Unit> = withContext(dispatchers.io) {
        val result = apiUnitCall { api.disconnect(agentId) }
        if (result is AppResult.Success) refresh()
        result
    }

    private suspend fun saveAndMap(result: AppResult<AgentDto>): AppResult<Agent> = when (result) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val domain = result.value.toDomain()
            if (domain == null) {
                AppResult.Failure(dev.agenthub.app.core.error.DataError.Unknown)
            } else {
                dao.upsertAll(listOf(result.value.toEntity(sortKey = System.currentTimeMillis())))
                AppResult.Success(domain)
            }
        }
    }

    private fun AgentDto.toEntity(sortKey: Long) =
        AgentEntity(id = id, json = json.encodeToString(AgentDto.serializer(), this), sortKey = sortKey)
}
