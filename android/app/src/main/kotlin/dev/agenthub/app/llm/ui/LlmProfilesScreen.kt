package dev.agenthub.app.llm.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.SkeletonList
import dev.agenthub.app.core.ui.messageRes
import dev.agenthub.app.llm.data.LlmProfile
import dev.agenthub.app.llm.data.LlmProfileValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmProfilesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: LlmProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.actionError) {
        state.actionError?.let {
            Toast.makeText(context, it.messageRes(), Toast.LENGTH_LONG).show()
            viewModel.onActionErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.llm_profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.llm_add)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> SkeletonList()
                state.showBlockingError -> ErrorState(state.error!!, onRetry = viewModel::refresh)
                state.showEmpty -> EmptyState(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.llm_empty_title),
                    message = stringResource(R.string.llm_empty_message),
                )
                else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                stringResource(R.string.llm_storage_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(state.profiles, key = { it.id }) { profile ->
                            ProfileCard(
                                profile = profile,
                                test = state.testStates[profile.id] ?: TestState.Idle,
                                onEdit = { onEdit(profile.id) },
                                onTest = { viewModel.onTest(profile) },
                                onDelete = { viewModel.onDeleteRequest(profile) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.deleteCandidate?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismiss,
            title = { Text(stringResource(R.string.llm_delete_title)) },
            text = { Text(stringResource(R.string.llm_delete_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirm) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::onDeleteDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProfileCard(profile: LlmProfile, test: TestState, onEdit: () -> Unit, onTest: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (profile.isDefault) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.llm_default)) })
            }
            Text("${profile.provider.label} · ${profile.model}", style = MaterialTheme.typography.bodyMedium)
            profile.baseUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Only the last four characters are ever available; the key itself never reaches the app.
            Text(
                stringResource(R.string.llm_key_stored, LlmProfileValidator.maskKey(profile.keyLast4)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTest, enabled = test != TestState.Running, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.llm_test))
                }
                TestStatus(test)
                Box(Modifier.weight(1f))
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun TestStatus(state: TestState) {
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> Text(stringResource(R.string.llm_test_running), style = MaterialTheme.typography.bodySmall)
        TestState.Passed -> Text(stringResource(R.string.llm_test_passed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        TestState.Failed -> Text(stringResource(R.string.llm_test_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
