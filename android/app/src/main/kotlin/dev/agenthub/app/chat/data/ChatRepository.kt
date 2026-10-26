package dev.agenthub.app.chat.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.database.ConversationDao
import dev.agenthub.app.core.database.ConversationEntity
import dev.agenthub.app.core.database.MessageDao
import dev.agenthub.app.core.database.MessageEntity
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.model.Conversation
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiCall
import dev.agenthub.app.core.network.apiUnitCall
import dev.agenthub.app.core.network.dto.MessageDto
import dev.agenthub.app.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

interface ChatRepository {
    fun observeConversations(agentId: String): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun refreshConversations(agentId: String): AppResult<Unit>

    /** Fetches only messages newer than what is cached, so reconnecting is cheap. */
    suspend fun refreshMessages(conversationId: String): AppResult<Unit>
    suspend fun newConversation(agentId: String): AppResult<Conversation>
    suspend fun deleteConversation(id: String): AppResult<Unit>
    suspend fun clearHistory(agentId: String): AppResult<Unit>
}

@Singleton
class DefaultChatRepository @Inject constructor(
    private val api: AgentHubApi,
    private val conversations: ConversationDao,
    private val messages: MessageDao,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : ChatRepository {

    override fun observeConversations(agentId: String): Flow<List<Conversation>> =
        conversations.observe(agentId).map { rows ->
            rows.map { Conversation(it.id, it.agentId, it.title, Instant.ofEpochMilli(it.updatedAtMillis)) }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messages.observe(conversationId).map { rows ->
            rows.mapNotNull { row ->
                runCatching { json.decodeFromString(MessageDto.serializer(), row.json).toDomain() }.getOrNull()
            }
        }

    override suspend fun refreshConversations(agentId: String): AppResult<Unit> = withContext(dispatchers.io) {
        when (val result = apiCall { api.conversations(agentId) }) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                conversations.replaceForAgent(agentId, result.value.map { it.toDomain().toEntity() })
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun refreshMessages(conversationId: String): AppResult<Unit> = withContext(dispatchers.io) {
        val last = messages.lastSeq(conversationId)
        when (val result = apiCall { api.messages(conversationId, last.takeIf { it > 0 }) }) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                messages.upsertAll(result.value.map { dto ->
                    MessageEntity(dto.id, dto.conversationId, dto.seq, json.encodeToString(MessageDto.serializer(), dto))
                })
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun newConversation(agentId: String): AppResult<Conversation> = withContext(dispatchers.io) {
        when (val result = apiCall { api.createConversation(agentId) }) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val domain = result.value.toDomain()
                conversations.upsertAll(listOf(domain.toEntity()))
                AppResult.Success(domain)
            }
        }
    }

    override suspend fun deleteConversation(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        val result = apiUnitCall { api.deleteConversation(id) }
        if (result is AppResult.Success) {
            messages.clearConversation(id)
            conversations.delete(id)
        }
        result
    }

    override suspend fun clearHistory(agentId: String): AppResult<Unit> = withContext(dispatchers.io) {
        val result = apiUnitCall { api.clearHistory(agentId) }
        if (result is AppResult.Success) {
            messages.clearForAgent(agentId)
            conversations.clearForAgent(agentId)
        }
        result
    }

    private fun Conversation.toEntity() = ConversationEntity(id, agentId, title, updatedAt.toEpochMilli())
}
