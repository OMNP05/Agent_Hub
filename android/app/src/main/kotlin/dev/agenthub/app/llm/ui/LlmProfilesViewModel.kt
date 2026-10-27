package dev.agenthub.app.llm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.llm.data.LlmProfile
import dev.agenthub.app.llm.data.LlmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TestState { Idle, Running, Passed, Failed }

data class LlmProfilesUiState(
    val profiles: List<LlmProfile> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: DataError? = null,
    val deleteCandidate: LlmProfile? = null,
    val testStates: Map<String, TestState> = emptyMap(),
    val actionError: DataError? = null,
) {
    val showEmpty: Boolean get() = !isLoading && error == null && profiles.isEmpty()
    val showBlockingError: Boolean get() = !isLoading && error != null && profiles.isEmpty()
}

@HiltViewModel
class LlmProfilesViewModel @Inject constructor(
    private val repository: LlmRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LlmProfilesUiState())
    val uiState: StateFlow<LlmProfilesUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.profiles()) {
                is AppResult.Success -> _uiState.update { it.copy(profiles = sorted(result.value), isLoading = false, isRefreshing = false) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.error) }
            }
        }
    }

    fun onTest(profile: LlmProfile) {
        if (_uiState.value.testStates[profile.id] == TestState.Running) return
        setTest(profile.id, TestState.Running)
        viewModelScope.launch {
            val state = when (val result = repository.test(profile.id)) {
                is AppResult.Success -> if (result.value.ok) TestState.Passed else TestState.Failed
                is AppResult.Failure -> TestState.Failed
            }
            setTest(profile.id, state)
        }
    }

    fun onDeleteRequest(profile: LlmProfile) = _uiState.update { it.copy(deleteCandidate = profile) }
    fun onDeleteDismiss() = _uiState.update { it.copy(deleteCandidate = null) }

    fun onDeleteConfirm() {
        val profile = _uiState.value.deleteCandidate ?: return
        _uiState.update { it.copy(deleteCandidate = null) }
        viewModelScope.launch {
            when (val result = repository.delete(profile.id)) {
                is AppResult.Success -> _uiState.update { it.copy(profiles = it.profiles.filterNot { p -> p.id == profile.id }) }
                is AppResult.Failure -> _uiState.update { it.copy(actionError = result.error) }
            }
        }
    }

    fun onActionErrorShown() = _uiState.update { it.copy(actionError = null) }

    private fun setTest(id: String, state: TestState) = _uiState.update { it.copy(testStates = it.testStates + (id to state)) }

    // The default profile first, then by name, so the list order is stable between refreshes.
    private fun sorted(list: List<LlmProfile>) = list.sortedWith(compareByDescending<LlmProfile> { it.isDefault }.thenBy { it.name.lowercase() })
}
