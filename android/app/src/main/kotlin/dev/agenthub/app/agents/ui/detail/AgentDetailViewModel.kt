package dev.agenthub.app.agents.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.agents.catalog.AgentTemplate
import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.agents.oauth.OAuthReturnBus
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.AgentUpdate
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ConnectionStatus
import dev.agenthub.app.navigation.AgentDetailRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentDetailUiState(
    val agent: Agent? = null,
    val template: AgentTemplate? = null,
    // Editable draft, kept apart from the saved agent so edits can be saved or discarded together.
    val name: String = "",
    val instructions: String = "",
    val permissions: AgentPermissions = AgentPermissions(),
    /** Saved permissions normalised against the template, so unchanged drafts compare equal. */
    val savedPermissions: AgentPermissions = AgentPermissions(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isWorking: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val error: DataError? = null,
    val notFound: Boolean = false,
) {
    val isDirty: Boolean
        get() = agent != null && (name != agent.name || instructions != agent.instructions || permissions != savedPermissions)
    val canSave: Boolean get() = isDirty && name.isNotBlank() && !isSaving
    val needsConnection: Boolean
        get() = agent != null && (agent.connection == null || agent.connection.status != ConnectionStatus.Active)
}

sealed interface AgentDetailEffect {
    data class OpenConsent(val url: String) : AgentDetailEffect
    data class Message(val text: DetailMessage) : AgentDetailEffect
    data object Deleted : AgentDetailEffect
}

enum class DetailMessage { Saved, Connected, ConnectionFailed, Disconnected }

@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AgentRepository,
    oauthReturns: OAuthReturnBus,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>(AgentDetailRoute::agentId.name).orEmpty()

    private val _uiState = MutableStateFlow(AgentDetailUiState())
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AgentDetailEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        repository.observeAgents()
            .onEach { agents -> applySaved(agents.firstOrNull { it.id == agentId }) }
            .launchIn(viewModelScope)

        oauthReturns.events
            .onEach { result ->
                if (result.agentId != agentId) return@onEach
                // Trust the backend, not the link: re-read the agent to see what was really granted.
                repository.refresh()
                _effects.trySend(
                    AgentDetailEffect.Message(if (result.ok) DetailMessage.Connected else DetailMessage.ConnectionFailed),
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val result = repository.refresh()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    notFound = state.agent == null && result is AppResult.Success,
                    error = if (state.agent == null) (result as? AppResult.Failure)?.error else null,
                )
            }
        }
    }

    private fun applySaved(agent: Agent?) {
        _uiState.update { state ->
            if (agent == null) return@update state
            val template = TemplateCatalog.byId(agent.templateId)
            // Keep unsaved edits when the cache emits again (for example after a refresh).
            val keepDraft = state.isDirty
            val saved = template?.let { PermissionRules.sanitize(it, agent.permissions) } ?: agent.permissions
            state.copy(
                agent = agent,
                template = template,
                savedPermissions = saved,
                name = if (keepDraft) state.name else agent.name,
                instructions = if (keepDraft) state.instructions else agent.instructions,
                permissions = if (keepDraft) state.permissions else saved,
                isLoading = false,
                notFound = false,
            )
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(60)) }
    fun onInstructionsChange(value: String) = _uiState.update { it.copy(instructions = value.take(2000)) }

    fun onToggle(toggleId: String, on: Boolean) = _uiState.update { state ->
        state.copy(permissions = state.permissions.copy(toggles = state.permissions.toggles + (toggleId to on)))
    }

    fun onModeChange(toggleId: String, mode: ApprovalMode) = _uiState.update { state ->
        val t = state.template ?: return@update state
        state.copy(permissions = PermissionRules.withToggleMode(t, state.permissions, toggleId, mode))
    }

    fun onDiscard() = _uiState.update { state ->
        val agent = state.agent ?: return@update state
        state.copy(name = agent.name, instructions = agent.instructions, permissions = state.savedPermissions)
    }

    fun onSave() {
        val state = _uiState.value
        val agent = state.agent ?: return
        if (!state.canSave) return
        val template = state.template
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val permissions = template?.let { PermissionRules.sanitize(it, state.permissions) } ?: state.permissions
            val enablesMore = PermissionRules.newlyEnabled(state.savedPermissions, permissions).isNotEmpty()
            val update = AgentUpdate(
                name = state.name.trim().takeIf { it != agent.name },
                instructions = state.instructions.trim().takeIf { it != agent.instructions },
                permissions = permissions.takeIf { it != state.savedPermissions },
            )
            when (val result = repository.update(agent.id, update)) {
                is AppResult.Failure -> _uiState.update { it.copy(isSaving = false, error = result.error) }
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, name = result.value.name, instructions = result.value.instructions) }
                    _effects.trySend(AgentDetailEffect.Message(DetailMessage.Saved))
                    // Turning on a new permission can need a new Google/GitHub scope: ask for consent again.
                    if (enablesMore && agent.connection?.status == ConnectionStatus.Active) startConnect()
                }
            }
        }
    }

    fun onTogglePause() {
        val agent = _uiState.value.agent ?: return
        val next = if (agent.status == AgentStatus.Active) AgentStatus.Paused else AgentStatus.Active
        runWork { repository.update(agent.id, AgentUpdate(status = next)).let { r -> (r as? AppResult.Failure) } }
    }

    fun startConnect() {
        val agent = _uiState.value.agent ?: return
        runWork {
            when (val result = repository.startConnect(agent)) {
                is AppResult.Success -> { _effects.trySend(AgentDetailEffect.OpenConsent(result.value)); null }
                is AppResult.Failure -> result
            }
        }
    }

    fun onDisconnect() {
        val agent = _uiState.value.agent ?: return
        runWork {
            when (val result = repository.disconnect(agent.id)) {
                is AppResult.Success -> { _effects.trySend(AgentDetailEffect.Message(DetailMessage.Disconnected)); null }
                is AppResult.Failure -> result
            }
        }
    }

    fun onDeleteRequest() = _uiState.update { it.copy(showDeleteConfirm = true) }
    fun onDeleteDismiss() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun onDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
        runWork {
            when (val result = repository.delete(agentId)) {
                is AppResult.Success -> { _effects.trySend(AgentDetailEffect.Deleted); null }
                is AppResult.Failure -> result
            }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    /** Runs [block]; a returned failure is shown as the screen error. */
    private fun runWork(block: suspend () -> AppResult.Failure?) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            val failure = block()
            _uiState.update { it.copy(isWorking = false, error = failure?.error) }
        }
    }
}
