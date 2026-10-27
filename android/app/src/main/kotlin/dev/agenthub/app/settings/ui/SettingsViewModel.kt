package dev.agenthub.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.ui.theme.ThemeMode
import dev.agenthub.app.settings.data.AccountRepository
import dev.agenthub.app.settings.data.SettingsStore
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

enum class ConfirmAction { KillSwitch, SignOut, DeleteAccount }

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val biometricLock: Boolean = false,
    val approvalNotifications: Boolean = true,
    val confirming: ConfirmAction? = null,
    val isWorking: Boolean = false,
    val error: DataError? = null,
)

sealed interface SettingsEffect {
    data object KillSwitchDone : SettingsEffect
    data class OpenExport(val url: String?) : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val account: AccountRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        settings.themeMode.onEach { m -> _uiState.update { it.copy(themeMode = m) } }.launchIn(viewModelScope)
        settings.biometricLock.onEach { b -> _uiState.update { it.copy(biometricLock = b) } }.launchIn(viewModelScope)
        settings.approvalNotifications.onEach { n -> _uiState.update { it.copy(approvalNotifications = n) } }.launchIn(viewModelScope)
    }

    fun onTheme(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
    fun onNotifications(enabled: Boolean) { viewModelScope.launch { settings.setApprovalNotifications(enabled) } }

    /** Turning the lock on is confirmed by a successful biometric prompt in the UI first. */
    fun onBiometricLock(enabled: Boolean) { viewModelScope.launch { settings.setBiometricLock(enabled) } }

    fun onConfirmRequest(action: ConfirmAction) = _uiState.update { it.copy(confirming = action) }
    fun onConfirmDismiss() = _uiState.update { it.copy(confirming = null) }

    fun onConfirm() {
        val action = _uiState.value.confirming ?: return
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(confirming = null, isWorking = true, error = null) }
        viewModelScope.launch {
            val failure: DataError? = when (action) {
                ConfirmAction.KillSwitch -> when (val r = account.killSwitch()) {
                    is AppResult.Success -> { _effects.trySend(SettingsEffect.KillSwitchDone); null }
                    is AppResult.Failure -> r.error
                }
                ConfirmAction.SignOut -> { auth.signOut(); null }
                ConfirmAction.DeleteAccount -> when (val r = account.deleteAccount()) {
                    // The server removed the account, so only the local session is left to clear.
                    is AppResult.Success -> { auth.signOut(); null }
                    is AppResult.Failure -> r.error
                }
            }
            _uiState.update { it.copy(isWorking = false, error = failure) }
        }
    }

    fun onExport() {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            when (val r = account.requestExport()) {
                is AppResult.Success -> { _uiState.update { it.copy(isWorking = false) }; _effects.trySend(SettingsEffect.OpenExport(r.value)) }
                is AppResult.Failure -> _uiState.update { it.copy(isWorking = false, error = r.error) }
            }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }
}
