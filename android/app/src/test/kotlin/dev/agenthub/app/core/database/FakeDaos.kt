package dev.agenthub.app.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAgentDao : AgentDao {
    private val rows = MutableStateFlow<List<AgentEntity>>(emptyList())
    val snapshot: List<AgentEntity> get() = rows.value

    override fun observeAll(): Flow<List<AgentEntity>> = rows.map { it.sortedByDescending { r -> r.sortKey } }
    override suspend fun upsertAll(items: List<AgentEntity>) {
        rows.value = rows.value.filterNot { old -> items.any { it.id == old.id } } + items
    }
    override suspend fun clear() { rows.value = emptyList() }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

class FakeConversationDao : ConversationDao {
    private val rows = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override fun observe(agentId: String): Flow<List<ConversationEntity>> =
        rows.map { it.filter { c -> c.agentId == agentId }.sortedByDescending { c -> c.updatedAtMillis } }
    override suspend fun upsertAll(items: List<ConversationEntity>) {
        rows.value = rows.value.filterNot { old -> items.any { it.id == old.id } } + items
    }
    override suspend fun clearForAgent(agentId: String) { rows.value = rows.value.filterNot { it.agentId == agentId } }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

class FakeMessageDao : MessageDao {
    private val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
    var conversationToAgent: Map<String, String> = emptyMap()
    override fun observe(conversationId: String): Flow<List<MessageEntity>> =
        rows.map { it.filter { m -> m.conversationId == conversationId }.sortedBy { m -> m.seq } }
    override suspend fun lastSeq(conversationId: String): Long =
        rows.value.filter { it.conversationId == conversationId }.maxOfOrNull { it.seq } ?: 0L
    override suspend fun upsertAll(items: List<MessageEntity>) {
        rows.value = rows.value.filterNot { old -> items.any { it.id == old.id } } + items
    }
    override suspend fun clearConversation(conversationId: String) {
        rows.value = rows.value.filterNot { it.conversationId == conversationId }
    }
    override suspend fun clearForAgent(agentId: String) {
        rows.value = rows.value.filterNot { conversationToAgent[it.conversationId] == agentId }
    }
}
