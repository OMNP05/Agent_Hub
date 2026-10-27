package dev.agenthub.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.core.ui.theme.ThemeMode
import dev.agenthub.app.settings.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class LockPhase {
    /** The preference has not been read yet; the app stays covered so a slow read never exposes it. */
    Loading,
    Locked,
    Open,
}

data class LockGateState(
    val themeMode: ThemeMode = ThemeMode.System,
    val lockEnabled: Boolean? = null,
    val unlocked: Boolean = false,
) {
    val phase: LockPhase
        get() = when {
            lockEnabled == null -> LockPhase.Loading
            lockEnabled && !unlocked -> LockPhase.Locked
            else -> LockPhase.Open
        }
}

/** Decides whether the app is covered by the local lock screen, and which theme is shown. */
@HiltViewModel
class LockGateViewModel @Inject constructor(
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LockGateState())
    val state: StateFlow<LockGateState> = _state.asStateFlow()

    init {
        settings.themeMode.onEach { m -> _state.update { it.copy(themeMode = m) } }.launchIn(viewModelScope)
        settings.biometricLock.onEach { on ->
            // Turning the lock on from Settings already proved the owner, so that session stays unlocked.
            _state.update { it.copy(lockEnabled = on, unlocked = if (on && it.lockEnabled == false) true else it.unlocked) }
        }.launchIn(viewModelScope)
    }

    fun onUnlocked() = _state.update { it.copy(unlocked = true) }

    /** The app went to the background: the lock applies again on return. */
    fun onBackgrounded() = _state.update { it.copy(unlocked = false) }
}
