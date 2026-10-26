package dev.agenthub.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.core.session.SessionStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthState { Loading, SignedOut, SignedIn }

@HiltViewModel
class RootViewModel @Inject constructor(
    store: SessionStore,
    private val auth: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = store.session
        .map { if (it == null) AuthState.SignedOut else AuthState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState.Loading)

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
