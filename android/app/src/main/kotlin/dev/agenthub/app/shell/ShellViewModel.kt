package dev.agenthub.app.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ShellUiState(val activeAgent: ActiveAgent? = null)

@HiltViewModel
class ShellViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    fun setActiveAgent(agent: ActiveAgent?) {
        _uiState.update { it.copy(activeAgent = agent) }
    }
}
