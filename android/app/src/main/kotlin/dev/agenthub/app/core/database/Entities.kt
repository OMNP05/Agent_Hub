package dev.agenthub.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache of the agent list so Home renders instantly and offline. [json] is the backend AgentDto;
 * no tokens or keys can be in it because the app never receives them.
 */
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val json: String,
    val sortKey: Long,
)

@Entity(tableName = "conversations", indices = [Index("agentId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val title: String?,
    val updatedAtMillis: Long,
)

@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "seq"], unique = true)])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val seq: Long,
    val json: String,
)
