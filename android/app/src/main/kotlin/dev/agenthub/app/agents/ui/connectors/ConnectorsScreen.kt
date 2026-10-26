package dev.agenthub.app.agents.ui.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.agents.ui.components.statusLabelRes
import dev.agenthub.app.agents.ui.home.HomeViewModel
import dev.agenthub.app.core.ui.components.AgentCard
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.SkeletonList

/** One row per agent showing its connection state; tapping opens the agent to reconnect or disconnect. */
@Composable
fun ConnectorsScreen(onOpenAgent: (String) -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> SkeletonList()
            state.showBlockingError -> ErrorState(state.error!!, onRetry = viewModel::refresh)
            state.agents.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Cable,
                title = stringResource(R.string.connectors_empty_title),
                message = stringResource(R.string.connectors_empty_message),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.agents, key = { it.id }) { agent ->
                    AgentCard(
                        title = agent.name,
                        subtitle = agent.connector.name + " · " + stringResource(agent.statusLabelRes()),
                        onClick = { onOpenAgent(agent.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
