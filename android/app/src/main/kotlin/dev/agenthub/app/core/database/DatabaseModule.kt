package dev.agenthub.app.core.database

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.agents.data.DefaultAgentRepository
import dev.agenthub.app.approvals.data.ApprovalRepository
import dev.agenthub.app.approvals.data.DefaultApprovalRepository
import dev.agenthub.app.chat.data.ChatRepository
import dev.agenthub.app.chat.data.DefaultChatRepository
import dev.agenthub.app.core.network.AgentHubApi
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "agenthub.db")
            // Everything cached here is re-fetchable from the backend, so a schema change may drop it.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun agentDao(db: AppDatabase): AgentDao = db.agentDao()
    @Provides fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()
    @Provides fun messageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides @Singleton
    fun agentHubApi(@Named("authed") retrofit: Retrofit): AgentHubApi = retrofit.create(AgentHubApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun agents(impl: DefaultAgentRepository): AgentRepository
    @Binds abstract fun chat(impl: DefaultChatRepository): ChatRepository
    @Binds abstract fun approvals(impl: DefaultApprovalRepository): ApprovalRepository
}
