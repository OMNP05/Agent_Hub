package dev.agenthub.app.approvals.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.approvals.data.ApprovalRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivityLogUiState(
    val agents: List<Agent> = emptyList(),
    /** Agent whose log is shown; null until the user has agents. */
    val selectedAgentId: String? = null,
    val entries: List<LogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: DataError? = null,
    val expandedId: String? = null,
) {
    val showEmpty: Boolean get() = selectedAgentId != null && !isLoading && error == null && entries.isEmpty()
}

@HiltViewModel
class ActivityLogViewModel @Inject constructor(
    private val repository: ApprovalRepository,
    agents: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityLogUiState())
    val uiState: StateFlow<ActivityLogUiState> = _uiState.asStateFlow()

    init {
        agents.observeAgents()
            .onEach { list ->
                val state = _uiState.value
                val stillThere = list.any { it.id == state.selectedAgentId }
                _uiState.update { it.copy(agents = list) }
                if (!stillThere) list.firstOrNull()?.let { select(it.id) } ?: _uiState.update { it.copy(selectedAgentId = null, entries = emptyList()) }
            }
            .launchIn(viewModelScope)
    }

    fun select(agentId: String) {
        if (agentId == _uiState.value.selectedAgentId && _uiState.value.entries.isNotEmpty()) return
        _uiState.update { it.copy(selectedAgentId = agentId, entries = emptyList(), endReached = false, error = null, expandedId = null) }
        load(reset = true)
    }

    fun retry() = load(reset = true)

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached || state.entries.isEmpty()) return
        load(reset = false)
    }

    fun toggleExpanded(id: String) = _uiState.update { it.copy(expandedId = if (it.expandedId == id) null else id) }

    private fun load(reset: Boolean) {
        val agentId = _uiState.value.selectedAgentId ?: return
        val before = if (reset) null else _uiState.value.entries.lastOrNull()?.createdAt?.toString()
        _uiState.update { it.copy(isLoading = reset, isLoadingMore = !reset, error = null) }
        viewModelScope.launch {
            val result = repository.logs(agentId, before = before, limit = PAGE)
            _uiState.update { state ->
                // The user may have switched agent while this was loading; drop the stale answer.
                if (state.selectedAgentId != agentId) return@update state
                when (result) {
                    is AppResult.Failure -> state.copy(isLoading = false, isLoadingMore = false, error = result.error.takeIf { reset || state.entries.isEmpty() })
                    is AppResult.Success -> {
                        val merged = if (reset) result.value else (state.entries + result.value).distinctBy { it.id }
                        state.copy(entries = merged, isLoading = false, isLoadingMore = false, endReached = result.value.size < PAGE)
                    }
                }
            }
        }
    }

    private companion object { const val PAGE = 30 }
}
