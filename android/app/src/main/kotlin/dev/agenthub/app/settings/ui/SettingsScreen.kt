package dev.agenthub.app.settings.ui

import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.core.ui.messageRes
import dev.agenthub.app.core.ui.theme.ThemeMode

@Composable
fun SettingsScreen(onOpenLlmProfiles: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    SettingsEffect.KillSwitchDone -> Toast.makeText(context, R.string.settings_kill_done, Toast.LENGTH_LONG).show()
                    is SettingsEffect.OpenExport ->
                        if (effect.url == null) {
                            Toast.makeText(context, R.string.settings_export_started, Toast.LENGTH_LONG).show()
                        } else {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { Toast.makeText(context, R.string.detail_no_browser, Toast.LENGTH_LONG).show() }
                        }
                }
            }
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it.messageRes(), Toast.LENGTH_LONG).show()
            viewModel.onErrorShown()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        Section(R.string.settings_llm)
        OutlinedButton(onClick = onOpenLlmProfiles, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.llm_profiles_title))
        }

        Section(R.string.settings_theme)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(selected = state.themeMode == mode, onClick = { viewModel.onTheme(mode) }, label = { Text(stringResource(mode.labelRes())) })
            }
        }

        Section(R.string.settings_security)
        BiometricRow(state.biometricLock, viewModel::onBiometricLock)

        Section(R.string.settings_notifications)
        SwitchRow(R.string.settings_notify_approvals, state.approvalNotifications, viewModel::onNotifications)

        Section(R.string.settings_safety)
        Button(
            onClick = { viewModel.onConfirmRequest(ConfirmAction.KillSwitch) },
            enabled = !state.isWorking,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.settings_kill)) }
        Text(stringResource(R.string.settings_kill_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section(R.string.settings_account)
        OutlinedButton(onClick = viewModel::onExport, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_export))
        }
        OutlinedButton(onClick = { viewModel.onConfirmRequest(ConfirmAction.SignOut) }, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_sign_out))
        }
        TextButton(onClick = { viewModel.onConfirmRequest(ConfirmAction.DeleteAccount) }, enabled = !state.isWorking, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_delete_account), color = MaterialTheme.colorScheme.error)
        }
    }

    state.confirming?.let { action ->
        AlertDialog(
            onDismissRequest = viewModel::onConfirmDismiss,
            title = { Text(stringResource(action.titleRes())) },
            text = { Text(stringResource(action.messageRes())) },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirm) { Text(stringResource(action.confirmRes()), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::onConfirmDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun BiometricRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val title = stringResource(R.string.lock_title)
    val unavailable = stringResource(R.string.settings_biometric_unavailable)
    SwitchRow(R.string.settings_biometric, enabled) { wantOn ->
        when {
            !wantOn -> onChange(false)
            activity == null || !AppLock.isAvailable(activity) -> Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show()
            // The lock is only turned on after the owner has proved themselves once, so they cannot lock themselves out.
            else -> AppLock.authenticate(activity, title, onSuccess = { onChange(true) }, onFailure = {})
        }
    }
}

@Composable
private fun SwitchRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Section(titleRes: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

private fun ConfirmAction.titleRes() = when (this) {
    ConfirmAction.KillSwitch -> R.string.settings_kill_title
    ConfirmAction.SignOut -> R.string.settings_sign_out_title
    ConfirmAction.DeleteAccount -> R.string.settings_delete_title
}

private fun ConfirmAction.messageRes() = when (this) {
    ConfirmAction.KillSwitch -> R.string.settings_kill_message
    ConfirmAction.SignOut -> R.string.settings_sign_out_message
    ConfirmAction.DeleteAccount -> R.string.settings_delete_message
}

private fun ConfirmAction.confirmRes() = when (this) {
    ConfirmAction.KillSwitch -> R.string.settings_kill_confirm
    ConfirmAction.SignOut -> R.string.settings_sign_out
    ConfirmAction.DeleteAccount -> R.string.settings_delete_confirm
}
