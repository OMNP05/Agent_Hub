package dev.agenthub.app.agents.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val agents: List<Agent> = emptyList(),
    /** True until the first cache read or network result arrives; drives the skeleton. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: DataError? = null,
) {
    val recent: List<Agent> get() = agents.filter { it.lastActivityAt != null }.sortedByDescending { it.lastActivityAt }.take(5)
    val showEmpty: Boolean get() = !isLoading && agents.isEmpty() && error == null
    val showBlockingError: Boolean get() = !isLoading && agents.isEmpty() && error != null
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var cacheLoaded = false
    private var networkDone = false

    init {
        repository.observeAgents()
            .onEach { agents ->
                cacheLoaded = true
                _uiState.update { it.copy(agents = agents, isLoading = !(networkDone || agents.isNotEmpty())) }
            }
            .catch { _uiState.update { s -> s.copy(isLoading = false, error = DataError.Unknown) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refresh()
            networkDone = true
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    isLoading = false,
                    error = (result as? AppResult.Failure)?.error,
                )
            }
        }
    }
}
