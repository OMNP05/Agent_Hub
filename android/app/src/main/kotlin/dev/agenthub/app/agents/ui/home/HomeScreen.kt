package dev.agenthub.app.agents.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.agents.ui.components.statusLabelRes
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.ui.components.AgentCard
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.SkeletonList

@Composable
fun HomeScreen(
    onOpenAgent: (String) -> Unit,
    onNewAgent: () -> Unit,
    onPickTemplate: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state, viewModel::refresh, onOpenAgent, onNewAgent, onPickTemplate)
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onNewAgent: () -> Unit,
    onPickTemplate: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> SkeletonList()
            state.showBlockingError -> ErrorState(state.error!!, onRetry = onRefresh)
            state.showEmpty -> EmptyState(
                icon = Icons.Outlined.SmartToy,
                title = stringResource(R.string.home_empty_title),
                message = stringResource(R.string.home_empty_message),
            )
            else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
                AgentList(state, onOpenAgent)
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewAgent,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.home_new_agent)) },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun AgentList(state: HomeUiState, onOpenAgent: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle(stringResource(R.string.home_title)) }
        items(state.agents, key = { it.id }) { agent -> AgentRow(agent, onOpenAgent) }

        if (state.recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_recent)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recent, key = { "recent-" + it.id }) { agent ->
                        AgentCard(
                            title = agent.name,
                            subtitle = stringResource(agent.statusLabelRes()),
                            onClick = { onOpenAgent(agent.id) },
                            modifier = Modifier.width(180.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(agent: Agent, onOpen: (String) -> Unit) {
    AgentCard(
        title = agent.name,
        subtitle = TemplateCatalog.byId(agent.templateId)?.displayName.orEmpty() +
            " · " + stringResource(agent.statusLabelRes()),
        onClick = { onOpen(agent.id) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}
