package dev.agenthub.app.agents.ui.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.oauth.launchCustomTab
import dev.agenthub.app.agents.ui.components.PermissionToggleRow
import dev.agenthub.app.agents.ui.components.statusLabelRes
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ConnectionStatus
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.SkeletonList
import dev.agenthub.app.core.ui.messageRes

@Composable
fun AgentDetailScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: AgentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AgentDetailEffect.OpenConsent ->
                        if (!launchCustomTab(context, effect.url)) {
                            Toast.makeText(context, R.string.detail_no_browser, Toast.LENGTH_LONG).show()
                        }
                    is AgentDetailEffect.Message -> Toast.makeText(context, effect.text.res(), Toast.LENGTH_SHORT).show()
                    AgentDetailEffect.Deleted -> onBack()
                }
            }
        }
    }

    AgentDetailContent(state, onBack, onOpenChat, viewModel)
}

private fun DetailMessage.res(): Int = when (this) {
    DetailMessage.Saved -> R.string.detail_saved
    DetailMessage.Connected -> R.string.detail_connected_message
    DetailMessage.ConnectionFailed -> R.string.detail_connection_failed
    DetailMessage.Disconnected -> R.string.detail_disconnected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentDetailContent(state: AgentDetailUiState, onBack: () -> Unit, onOpenChat: () -> Unit, vm: AgentDetailViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.agent?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val agent = state.agent
            when {
                state.isLoading && agent == null -> SkeletonList()
                agent == null && state.error != null -> ErrorState(state.error, onRetry = null)
                agent == null -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(R.string.error_generic),
                    message = stringResource(R.string.detail_not_found),
                )
                else -> DetailBody(state, onOpenChat, vm)
            }
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = vm::onDeleteDismiss,
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = vm::onDeleteConfirm) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::onDeleteDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailBody(state: AgentDetailUiState, onOpenChat: () -> Unit, vm: AgentDetailViewModel) {
    val agent = state.agent ?: return
    val template = state.template
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(agent.statusLabelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        if (!state.needsConnection) {
            Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.chat_open))
            }
        }

        ConnectionSection(state, vm)
        HorizontalDivider()

        OutlinedTextField(
            value = state.name,
            onValueChange = vm::onNameChange,
            label = { Text(stringResource(R.string.setup_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.instructions,
            onValueChange = vm::onInstructionsChange,
            label = { Text(stringResource(R.string.setup_instructions)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        if (template != null) {
            Text(stringResource(R.string.setup_permissions), style = MaterialTheme.typography.titleLarge)
            template.toggles.forEach { toggle ->
                PermissionToggleRow(
                    toggle = toggle,
                    enabled = state.permissions.toggles[toggle.id] == true,
                    mode = PermissionRules.toggleMode(template, state.permissions, toggle.id),
                    onToggle = { vm.onToggle(toggle.id, it) },
                    onModeChange = { mode: ApprovalMode -> vm.onModeChange(toggle.id, mode) },
                )
            }
        }

        state.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }

        if (state.isDirty) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::onDiscard, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.detail_discard))
                }
                Button(onClick = vm::onSave, enabled = state.canSave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.detail_save))
                }
            }
        }

        HorizontalDivider()
        OutlinedButton(
            onClick = vm::onTogglePause,
            enabled = !state.isWorking && !state.needsConnection,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (agent.status == AgentStatus.Active) R.string.detail_pause else R.string.detail_resume))
        }
        Button(
            onClick = vm::onDeleteRequest,
            enabled = !state.isWorking,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.detail_delete))
        }
    }
}

@Composable
private fun ConnectionSection(state: AgentDetailUiState, vm: AgentDetailViewModel) {
    val connection = state.agent?.connection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.detail_connection), style = MaterialTheme.typography.titleLarge)
        val active = connection?.status == ConnectionStatus.Active
        Text(
            stringResource(
                when {
                    active -> R.string.detail_connected
                    connection == null -> R.string.detail_not_connected
                    else -> R.string.agent_needs_reauth
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (active && connection.scopesGranted.isNotEmpty()) {
            Text(
                stringResource(R.string.detail_scopes, connection.scopesGranted.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::startConnect, enabled = !state.isWorking, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (connection == null) R.string.detail_connect else R.string.detail_reconnect))
            }
            if (connection != null) {
                OutlinedButton(onClick = vm::onDisconnect, enabled = !state.isWorking, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.detail_disconnect))
                }
            }
        }
    }
}
