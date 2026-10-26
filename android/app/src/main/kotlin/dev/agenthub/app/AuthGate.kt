package dev.agenthub.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.auth.ui.AuthState
import dev.agenthub.app.auth.ui.RootViewModel
import dev.agenthub.app.auth.ui.SignInScreen

/** Shows sign-in until a session exists; the stored session decides, never an in-memory flag. */
@Composable
fun AuthGate(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            AuthState.Loading -> Box(Modifier.fillMaxSize())
            AuthState.SignedOut -> SignInScreen()
            AuthState.SignedIn -> AppRoot()
        }
    }
}
