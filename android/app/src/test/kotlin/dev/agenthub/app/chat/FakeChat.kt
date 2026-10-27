package dev.agenthub.app.chat

import dev.agenthub.app.chat.data.ChatRepository
import dev.agenthub.app.chat.data.ChatStream
import dev.agenthub.app.chat.data.StreamEvent
import dev.agenthub.app.chat.data.StreamException
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Conversation
import dev.agenthub.app.core.model.Message
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.core.model.RunState
import dev.agenthub.app.core.model.RunStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import java.time.Instant

fun conversation(id: String, agentId: String = "a1", at: Long = 0) = Conversation(id, agentId, null, Instant.ofEpochMilli(at))

class FakeChatRepository : ChatRepository {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())

    var refreshConversationsResult: AppResult<Unit> = AppResult.Success(Unit)
    var runStateResult: AppResult<RunState> = AppResult.Success(RunState("r1", RunStatus.Completed, null))
    var newConversationResult: AppResult<Conversation>? = null
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)

    var cancelledRun: String? = null
    var clearedAgent: String? = null
    var deletedId: String? = null
    var newConversationCalls = 0

    override fun observeConversations(agentId: String): Flow<List<Conversation>> = conversations
    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messages.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun refreshConversations(agentId: String) = refreshConversationsResult

    // yield() throws if the caller was cancelled, which is what the real withContext(io) repository does.
    override suspend fun refreshMessages(conversationId: String): AppResult<Unit> { yield(); return AppResult.Success(Unit) }

    override suspend fun newConversation(agentId: String): AppResult<Conversation> {
        newConversationCalls++
        val result = newConversationResult ?: AppResult.Success(conversation("c${newConversationCalls + 100}", agentId, at = 10_000L + newConversationCalls))
        if (result is AppResult.Success) conversations.value = listOf(result.value) + conversations.value
        return result
    }

    override suspend fun deleteConversation(id: String): AppResult<Unit> {
        deletedId = id
        if (deleteResult is AppResult.Success) conversations.value = conversations.value.filterNot { it.id == id }
        return deleteResult
    }

    override suspend fun clearHistory(agentId: String): AppResult<Unit> {
        clearedAgent = agentId
        conversations.value = emptyList()
        messages.value = emptyList()
        return AppResult.Success(Unit)
    }

    override suspend fun runState(runId: String) = runStateResult
    override suspend fun cancelRun(runId: String): AppResult<Unit> { cancelledRun = runId; return AppResult.Success(Unit) }
}

class FakeChatStream : ChatStream {
    var sendScript: () -> Flow<StreamEvent> = { flow { } }
    var resumeScript: (String, String?) -> Flow<StreamEvent> = { _, _ -> flow { } }

    var sentMessage: String? = null
    var sentConversation: String? = null
    var resumedRun: String? = null
    var resumedFrom: String? = null
    var resumeCalls = 0

    override fun send(agentId: String, conversationId: String, message: String): Flow<StreamEvent> {
        sentMessage = message
        sentConversation = conversationId
        return sendScript()
    }

    override fun resume(runId: String, lastEventId: String?): Flow<StreamEvent> {
        resumeCalls++
        resumedRun = runId
        resumedFrom = lastEventId
        return resumeScript(runId, lastEventId)
    }
}

fun ev(id: String?, event: RunEvent) = StreamEvent(id, event)

/** Emits [events] then keeps the stream open forever, like a run that is still working. */
fun hanging(vararg events: StreamEvent): Flow<StreamEvent> = flow {
    events.forEach { emit(it) }
    awaitCancellation()
}

fun failingAfter(error: DataError, vararg events: StreamEvent): Flow<StreamEvent> = flow {
    events.forEach { emit(it) }
    throw StreamException(error)
}
