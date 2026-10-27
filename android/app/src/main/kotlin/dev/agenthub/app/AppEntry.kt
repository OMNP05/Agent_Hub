package dev.agenthub.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.core.ui.theme.AgentHubTheme
import dev.agenthub.app.settings.ui.AppLock
import dev.agenthub.app.settings.ui.LockGateViewModel
import dev.agenthub.app.settings.ui.LockPhase
import dev.agenthub.app.settings.ui.LockScreen

/** Applies the chosen theme and covers the whole app with the lock screen when the biometric lock is on. */
@Composable
fun AppEntry(viewModel: LockGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The lock applies again whenever the app leaves the foreground.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.onBackgrounded() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val title = stringResource(R.string.lock_title)
    val prompt: () -> Unit = {
        (context as? FragmentActivity)?.let { activity ->
            AppLock.authenticate(activity, title, onSuccess = viewModel::onUnlocked, onFailure = {})
        }
    }
    // Ask as soon as the app is shown locked; the Unlock button covers a cancelled prompt.
    LaunchedEffect(state.phase) { if (state.phase == LockPhase.Locked) prompt() }

    AgentHubTheme(mode = state.themeMode) {
        when (state.phase) {
            // Nothing from the app is composed until the lock state is known, so it can never flash content.
            LockPhase.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) }
            LockPhase.Locked -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { LockScreen(onUnlock = prompt) }
            LockPhase.Open -> AuthGate()
        }
    }
}
