package dev.agenthub.app.agents.ui.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.agents.catalog.AgentTemplate
import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.NewAgent
import dev.agenthub.app.navigation.AgentSetupRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class AgentSetupUiState(
    val template: AgentTemplate?,
    val name: String = "",
    val instructions: String = "",
    val permissions: AgentPermissions = AgentPermissions(),
    val isSaving: Boolean = false,
    val error: DataError? = null,
) {
    val canSave: Boolean get() = template != null && name.isNotBlank() && !isSaving
}

sealed interface AgentSetupEffect {
    /** Agent was created; the screen opens the provider consent page for it. */
    data class OpenConsent(val agent: Agent, val url: String) : AgentSetupEffect
    /** Agent was created but consent could not be started; the user can retry from the agent screen. */
    data class CreatedWithoutConsent(val agentId: String, val error: DataError) : AgentSetupEffect
}

@HiltViewModel
class AgentSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AgentRepository,
) : ViewModel() {

    // Type-safe navigation stores each route argument in the SavedStateHandle under its property name.
    private val template = TemplateCatalog.byId(savedStateHandle.get<String>(AgentSetupRoute::templateId.name))

    private val _uiState = MutableStateFlow(
        AgentSetupUiState(
            template = template,
            name = template?.displayName.orEmpty(),
            instructions = template?.defaultInstructions.orEmpty(),
            permissions = template?.let(PermissionRules::defaults) ?: AgentPermissions(),
        ),
    )
    val uiState: StateFlow<AgentSetupUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AgentSetupEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(MAX_NAME)) }
    fun onInstructionsChange(value: String) = _uiState.update { it.copy(instructions = value.take(MAX_INSTRUCTIONS)) }

    fun onToggle(toggleId: String, on: Boolean) = _uiState.update { state ->
        state.copy(permissions = state.permissions.copy(toggles = state.permissions.toggles + (toggleId to on)))
    }

    fun onModeChange(toggleId: String, mode: ApprovalMode) = _uiState.update { state ->
        val t = state.template ?: return@update state
        state.copy(permissions = PermissionRules.withToggleMode(t, state.permissions, toggleId, mode))
    }

    fun onSave() {
        val state = _uiState.value
        val t = state.template ?: return
        if (!state.canSave) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val request = NewAgent(
                templateId = t.id,
                name = state.name.trim(),
                instructions = state.instructions.trim(),
                permissions = PermissionRules.sanitize(t, state.permissions),
                timeZone = ZoneId.systemDefault().id,
            )
            when (val created = repository.create(request)) {
                is AppResult.Failure -> _uiState.update { it.copy(isSaving = false, error = created.error) }
                is AppResult.Success -> {
                    val agent = created.value
                    _uiState.update { it.copy(isSaving = false) }
                    when (val consent = repository.startConnect(agent)) {
                        is AppResult.Success -> _effects.trySend(AgentSetupEffect.OpenConsent(agent, consent.value))
                        is AppResult.Failure -> _effects.trySend(AgentSetupEffect.CreatedWithoutConsent(agent.id, consent.error))
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_NAME = 60
        const val MAX_INSTRUCTIONS = 2000
    }
}
