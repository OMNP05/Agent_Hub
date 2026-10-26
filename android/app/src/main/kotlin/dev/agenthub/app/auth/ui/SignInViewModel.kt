package dev.agenthub.app.auth.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.auth.data.GoogleIdTokenProvider
import dev.agenthub.app.auth.data.IdTokenResult
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val isLoading: Boolean = false,
    val error: DataError? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val idTokens: GoogleIdTokenProvider,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    /** [context] must be an Activity context so the Google account picker can be shown. */
    fun onSignInClick(context: Context) {
        if (_uiState.value.isLoading) return
        _uiState.value = SignInUiState(isLoading = true)
        viewModelScope.launch {
            when (val token = idTokens.request(context)) {
                is IdTokenResult.Cancelled -> _uiState.value = SignInUiState()
                is IdTokenResult.Failed -> _uiState.value = SignInUiState(error = token.error)
                is IdTokenResult.Token -> when (val result = auth.signInWithGoogle(token.idToken, token.nonce)) {
                    // On success the session store flips and the root swaps to the main app.
                    is AppResult.Success -> _uiState.update { it.copy(isLoading = false) }
                    is AppResult.Failure -> _uiState.value = SignInUiState(error = result.error)
                }
            }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }
}
