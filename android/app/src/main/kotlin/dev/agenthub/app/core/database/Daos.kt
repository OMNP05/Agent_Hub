package dev.agenthub.app.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY sortKey DESC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AgentEntity>)

    @Query("DELETE FROM agents")
    suspend fun clear()

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)

    /** Replaces the whole list so agents deleted elsewhere disappear from the cache. */
    @Transaction
    suspend fun replaceAll(items: List<AgentEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY updatedAtMillis DESC")
    fun observe(agentId: String): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE agentId = :agentId")
    suspend fun clearForAgent(agentId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun replaceForAgent(agentId: String, items: List<ConversationEntity>) {
        clearForAgent(agentId)
        upsertAll(items)
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun lastSeq(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId IN (SELECT id FROM conversations WHERE agentId = :agentId)")
    suspend fun clearForAgent(agentId: String)
}

@Database(
    entities = [AgentEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
