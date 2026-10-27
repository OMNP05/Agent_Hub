package dev.agenthub.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.push.DeviceRepository
import dev.agenthub.app.push.NotificationNavigation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ShellUiState(val activeAgent: ActiveAgent? = null)

@HiltViewModel
class ShellViewModel @Inject constructor(
    notificationNavigation: NotificationNavigation,
    private val devices: DeviceRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    /** Approvals the user asked to open from a notification, consumed by the root to navigate. */
    val openApprovals: Flow<String> = notificationNavigation.approvals

    init {
        // Register this device for pushes once the signed-in shell is shown; a no-op without Firebase configured.
        viewModelScope.launch { devices.register() }
    }

    fun setActiveAgent(agent: ActiveAgent?) {
        _uiState.update { it.copy(activeAgent = agent) }
    }
}
