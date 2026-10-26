package dev.agenthub.app.agents.ui.setup

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.messageRes
import androidx.compose.material.icons.outlined.ErrorOutline

@Composable
fun AgentSetupScreen(
    onBack: () -> Unit,
    onDone: (agentId: String) -> Unit,
    viewModel: AgentSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AgentSetupEffect.OpenConsent -> {
                        if (!launchCustomTab(context, effect.url)) {
                            Toast.makeText(context, R.string.detail_no_browser, Toast.LENGTH_LONG).show()
                        }
                        onDone(effect.agent.id)
                    }
                    is AgentSetupEffect.CreatedWithoutConsent -> {
                        Toast.makeText(context, effect.error.messageRes(), Toast.LENGTH_LONG).show()
                        onDone(effect.agentId)
                    }
                }
            }
        }
    }

    AgentSetupContent(
        state = state,
        onBack = onBack,
        onNameChange = viewModel::onNameChange,
        onInstructionsChange = viewModel::onInstructionsChange,
        onToggle = viewModel::onToggle,
        onModeChange = viewModel::onModeChange,
        onSave = viewModel::onSave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSetupContent(
    state: AgentSetupUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onModeChange: (String, dev.agenthub.app.core.model.ApprovalMode) -> Unit,
    onSave: () -> Unit,
) {
    val template = state.template
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title, template?.displayName.orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        if (template == null) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.error_generic),
                message = stringResource(R.string.setup_unknown_template),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.setup_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.instructions,
                onValueChange = onInstructionsChange,
                label = { Text(stringResource(R.string.setup_instructions)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.setup_permissions), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setup_permissions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            template.toggles.forEach { toggle ->
                PermissionToggleRow(
                    toggle = toggle,
                    enabled = state.permissions.toggles[toggle.id] == true,
                    mode = PermissionRules.toggleMode(template, state.permissions, toggle.id),
                    onToggle = { onToggle(toggle.id, it) },
                    onModeChange = { onModeChange(toggle.id, it) },
                )
            }
            state.error?.let {
                Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.setup_connect_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.setup_connect))
            }
        }
    }
}
