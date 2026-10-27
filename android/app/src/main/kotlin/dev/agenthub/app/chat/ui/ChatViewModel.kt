package dev.agenthub.app.chat.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.chat.data.ChatRepository
import dev.agenthub.app.chat.data.ChatStream
import dev.agenthub.app.chat.data.StreamEvent
import dev.agenthub.app.chat.data.StreamException
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Conversation
import dev.agenthub.app.core.model.RunEvent
import dev.agenthub.app.core.model.RunStatus
import dev.agenthub.app.navigation.ChatRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class ChatUiState(
    val agentName: String = "",
    val items: List<ChatItem> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    /** Set when the first load fails and nothing is cached; the screen shows a full-screen error. */
    val loadError: DataError? = null,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String? = null,
    val showHistory: Boolean = false,
    val showClearConfirm: Boolean = false,
    val actionError: DataError? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !isRunning && !isLoading
    val isEmpty: Boolean get() = !isLoading && loadError == null && items.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    agents: AgentRepository,
    private val stream: ChatStream,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>(ChatRoute::agentId.name).orEmpty()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val currentConversation = MutableStateFlow<String?>(null)
    private var latestHistory: List<ChatItem> = emptyList()
    private var runJob: Job? = null
    private var runId: String? = null
    private var lastEventId: String? = null
    private var terminal: RunEvent? = null

    init {
        agents.observeAgents()
            .onEach { list -> list.firstOrNull { it.id == agentId }?.let { a -> _uiState.update { it.copy(agentName = a.name) } } }
            .launchIn(viewModelScope)

        chats.observeConversations(agentId)
            .onEach { list ->
                _uiState.update { it.copy(conversations = list) }
                // The ongoing conversation is the most recently updated one (architecture 3.8).
                if (currentConversation.value == null && list.isNotEmpty()) select(list.first().id)
            }
            .launchIn(viewModelScope)

        currentConversation
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeMessages(id) }
            .onEach { messages ->
                latestHistory = ChatReducer.fromHistory(messages)
                if (!_uiState.value.isRunning) _uiState.update { it.copy(items = latestHistory) }
            }
            .launchIn(viewModelScope)

        load()
    }

    private fun load() {
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            val result = chats.refreshConversations(agentId)
            val conversationId = currentConversation.value
            if (conversationId != null) chats.refreshMessages(conversationId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadError = (result as? AppResult.Failure)?.error.takeIf { _ -> it.conversations.isEmpty() },
                )
            }
        }
    }

    fun onRetry() = load()

    private fun select(id: String?) {
        currentConversation.value = id
        _uiState.update { it.copy(currentConversationId = id, showHistory = false) }
        if (id != null) viewModelScope.launch { chats.refreshMessages(id) }
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(input = value.take(MAX_MESSAGE)) }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return
        val text = state.input.trim()
        _uiState.update {
            it.copy(
                input = "",
                isRunning = true,
                actionError = null,
                items = ChatReducer.withUserMessage(it.items, UUID.randomUUID().toString(), text),
            )
        }
        runId = null
        lastEventId = null
        terminal = null
        runJob = viewModelScope.launch {
            val conversationId = currentConversation.value ?: createConversation() ?: return@launch finishRun()
            try {
                runStream(stream.send(agentId, conversationId, text))
            } finally {
                // Stop cancels this job; cleanup still has to suspend (refresh history) and must not be cancelled.
                withContext(NonCancellable) { finishRun() }
            }
        }
    }

    private suspend fun createConversation(): String? =
        when (val result = chats.newConversation(agentId)) {
            is AppResult.Success -> {
                currentConversation.value = result.value.id
                _uiState.update { it.copy(currentConversationId = result.value.id) }
                result.value.id
            }
            is AppResult.Failure -> {
                applyEvent(RunEvent.Failed(result.error))
                null
            }
        }

    /** Collects the stream; on a dropped connection resumes from the last event id a few times. */
    private suspend fun runStream(first: Flow<StreamEvent>) {
        var source = first
        var attempts = 0
        while (true) {
            try {
                source.collect { event ->
                    event.id?.let { lastEventId = it }
                    if (event.event is RunEvent.Started) runId = event.event.runId
                    if (event.event.isTerminal) terminal = event.event
                    applyEvent(event.event)
                }
                if (terminal == null) checkRunAfterClose()
                return
            } catch (e: StreamException) {
                val id = runId
                if (id == null || attempts >= MAX_RESUMES || e.error != DataError.Network) {
                    terminal = RunEvent.Failed(e.error)
                    applyEvent(RunEvent.Failed(e.error))
                    return
                }
                attempts++
                delay(RESUME_DELAY_MS * attempts)
                source = stream.resume(id, lastEventId)
            }
        }
    }

    /** The server closed the stream without a final event: ask for the run's real state instead of guessing. */
    private suspend fun checkRunAfterClose() {
        val id = runId ?: return applyEvent(RunEvent.Failed(DataError.Network)).also { terminal = RunEvent.Failed(DataError.Network) }
        when (val state = chats.runState(id)) {
            is AppResult.Failure -> { terminal = RunEvent.Failed(state.error); applyEvent(RunEvent.Failed(state.error)) }
            is AppResult.Success -> when (state.value.status) {
                RunStatus.Completed -> { terminal = RunEvent.Completed(null); applyEvent(RunEvent.Completed(null)) }
                RunStatus.Failed, RunStatus.Expired -> {
                    val error = state.value.errorCode ?: DataError.Unknown
                    terminal = RunEvent.Failed(error); applyEvent(RunEvent.Failed(error))
                }
                RunStatus.Cancelled -> terminal = RunEvent.Failed(DataError.Unknown)
                RunStatus.AwaitingApproval -> Unit // the pending approval is listed in Activity
                RunStatus.Queued, RunStatus.Running -> {
                    terminal = RunEvent.Failed(DataError.Network); applyEvent(RunEvent.Failed(DataError.Network))
                }
            }
        }
    }

    private fun applyEvent(event: RunEvent) = _uiState.update { it.copy(items = ChatReducer.apply(it.items, event)) }

    private suspend fun finishRun() {
        val conversationId = currentConversation.value
        val refreshed = conversationId?.let { chats.refreshMessages(it) } is AppResult.Success
        val completed = terminal is RunEvent.Completed
        _uiState.update { state ->
            state.copy(
                isRunning = false,
                // Stored history replaces the live rows only after a completed run; failures and pending
                // approvals are not stored, so their rows must stay visible.
                items = if (completed && refreshed) latestHistory else ChatReducer.settle(state.items),
            )
        }
        runJob = null
    }

    fun onStop() {
        val job = runJob ?: return
        val id = runId
        viewModelScope.launch {
            if (id != null) chats.cancelRun(id)
            job.cancel()
        }
    }

    fun onNewChat() {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            // Do not stack empty conversations: starting a new chat on an empty one is a no-op.
            if (_uiState.value.items.isEmpty()) return@launch
            when (val result = chats.newConversation(agentId)) {
                is AppResult.Success -> {
                    latestHistory = emptyList()
                    _uiState.update { it.copy(items = emptyList()) }
                    select(result.value.id)
                }
                is AppResult.Failure -> _uiState.update { it.copy(actionError = result.error) }
            }
        }
    }

    fun onHistoryToggle(show: Boolean) = _uiState.update { it.copy(showHistory = show) }

    fun onSelectConversation(id: String) {
        if (_uiState.value.isRunning || id == currentConversation.value) return _uiState.update { it.copy(showHistory = false) }
        _uiState.update { it.copy(items = emptyList()) }
        select(id)
    }

    fun onDeleteConversation(id: String) {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            when (val result = chats.deleteConversation(id)) {
                is AppResult.Failure -> _uiState.update { it.copy(actionError = result.error) }
                is AppResult.Success -> if (id == currentConversation.value) {
                    val next = _uiState.value.conversations.firstOrNull { it.id != id }?.id
                    _uiState.update { it.copy(items = emptyList()) }
                    select(next)
                }
            }
        }
    }

    fun onClearHistoryRequest() = _uiState.update { it.copy(showClearConfirm = true, showHistory = false) }
    fun onClearHistoryDismiss() = _uiState.update { it.copy(showClearConfirm = false) }

    fun onClearHistoryConfirm() {
        _uiState.update { it.copy(showClearConfirm = false) }
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            when (val result = chats.clearHistory(agentId)) {
                is AppResult.Failure -> _uiState.update { it.copy(actionError = result.error) }
                is AppResult.Success -> {
                    latestHistory = emptyList()
                    _uiState.update { it.copy(items = emptyList()) }
                    select(null)
                }
            }
        }
    }

    fun onActionErrorShown() = _uiState.update { it.copy(actionError = null) }

    private companion object {
        const val MAX_MESSAGE = 4000
        const val MAX_RESUMES = 3
        const val RESUME_DELAY_MS = 1_000L
    }
}
