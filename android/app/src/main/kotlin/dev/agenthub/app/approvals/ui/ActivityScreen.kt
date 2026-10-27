package dev.agenthub.app.approvals.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.approvals.data.Decision
import dev.agenthub.app.core.model.Approval
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.LogEntry
import dev.agenthub.app.core.model.RiskLevel
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.RiskBadge
import dev.agenthub.app.core.ui.components.SkeletonList
import dev.agenthub.app.core.ui.messageRes

@Composable
fun ActivityScreen(
    highlightApprovalId: String?,
    approvalsViewModel: ApprovalsViewModel = hiltViewModel(),
    logViewModel: ActivityLogViewModel = hiltViewModel(),
) {
    val approvals by approvalsViewModel.uiState.collectAsStateWithLifecycle()
    val log by logViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(highlightApprovalId) { approvalsViewModel.highlight(highlightApprovalId) }
    LaunchedEffect(approvalsViewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            approvalsViewModel.effects.collect { effect ->
                val text = when (effect) {
                    is ApprovalsEffect.Decided ->
                        if (effect.decision == Decision.Approve) R.string.approval_decided_approved else R.string.approval_decided_rejected
                    ApprovalsEffect.AlreadyHandled -> R.string.approval_already_handled
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(approvals.error, approvals.approvals.size) {
        // A failed decision (not a failed load) is surfaced as a toast; a failed load has its own screen.
        if (approvals.error != null && approvals.approvals.isNotEmpty()) {
            Toast.makeText(context, approvals.error!!.messageRes(), Toast.LENGTH_LONG).show()
            approvalsViewModel.onErrorShown()
        }
    }

    ActivityContent(
        approvals = approvals,
        log = log,
        onRefresh = approvalsViewModel::refresh,
        onApprove = approvalsViewModel::onApproveTap,
        onReject = approvalsViewModel::onRejectTap,
        onSelectAgent = logViewModel::select,
        onLoadMore = logViewModel::loadMore,
        onToggleLog = logViewModel::toggleExpanded,
        onRetryLog = logViewModel::retry,
    )

    approvals.confirming?.let { approval ->
        AlertDialog(
            onDismissRequest = approvalsViewModel::onConfirmDismiss,
            title = { Text(stringResource(R.string.approval_confirm_title)) },
            text = { Text(stringResource(R.string.approval_confirm_message, ToolInfo.label(approval.tool))) },
            confirmButton = {
                TextButton(onClick = approvalsViewModel::onConfirmApprove) { Text(stringResource(R.string.approval_approve)) }
            },
            dismissButton = {
                TextButton(onClick = approvalsViewModel::onConfirmDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun ActivityContent(
    approvals: ApprovalsUiState,
    log: ActivityLogUiState,
    onRefresh: () -> Unit,
    onApprove: (Approval) -> Unit,
    onReject: (Approval) -> Unit,
    onSelectAgent: (String) -> Unit,
    onLoadMore: () -> Unit,
    onToggleLog: (String) -> Unit,
    onRetryLog: () -> Unit,
) {
    when {
        approvals.isLoading -> SkeletonList()
        approvals.showBlockingError -> ErrorState(approvals.error!!, onRetry = onRefresh)
        else -> PullToRefreshBox(isRefreshing = approvals.isRefreshing, onRefresh = onRefresh) {
            val listState = rememberLazyListState()
            val highlighted = approvals.highlightId
            LaunchedEffect(highlighted, approvals.pending.size) {
                val index = approvals.pending.indexOfFirst { it.id == highlighted }
                if (index >= 0) listState.animateScrollToItem(index + 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NotificationBanner() }
                if (approvals.pending.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.approvals_title)) }
                    items(approvals.pending, key = { "approval-" + it.id }) { approval ->
                        ApprovalCard(
                            approval = approval,
                            busy = approval.id in approvals.inFlight,
                            highlighted = approval.id == highlighted,
                            onApprove = { onApprove(approval) },
                            onReject = { onReject(approval) },
                        )
                    }
                } else if (approvals.showEmpty) {
                    item {
                        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp)) {
                            EmptyState(
                                icon = Icons.Outlined.TaskAlt,
                                title = stringResource(R.string.approvals_empty_title),
                                message = stringResource(R.string.approvals_empty_message),
                            )
                        }
                    }
                }
                logSection(log, onSelectAgent, onLoadMore, onToggleLog, onRetryLog)
            }
        }
    }
}

@Composable
private fun NotificationBanner() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.activity_notifications_denied), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.activity_enable_notifications))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun ApprovalCard(
    approval: Approval,
    busy: Boolean,
    highlighted: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val risk = ToolInfo.risk(approval.tool)
    val lines = ArgsFormatter.format(approval.args)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            approval.agentName?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.approval_wants_to, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(ToolInfo.label(approval.tool), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                RiskBadge(risk)
            }
            HorizontalDivider()
            // The full stored arguments, never shortened: this is what will run if the user approves.
            lines.forEach { line ->
                Column {
                    Text(line.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                stringResource(if (risk == RiskLevel.Dangerous) R.string.approval_dangerous_notice else R.string.approval_exact_notice),
                style = MaterialTheme.typography.bodySmall,
                color = if (risk == RiskLevel.Dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.approval_reject))
                }
                Button(
                    onClick = onApprove,
                    enabled = !busy && approval.status == ApprovalStatus.Pending,
                    colors = if (risk == RiskLevel.Dangerous) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.approval_approve))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.logSection(
    log: ActivityLogUiState,
    onSelectAgent: (String) -> Unit,
    onLoadMore: () -> Unit,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
) {
    item { SectionTitle(androidx.compose.ui.res.stringResource(R.string.activity_log_title)) }
    if (log.agents.isNotEmpty()) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                log.agents.forEach { agent ->
                    FilterChip(
                        selected = agent.id == log.selectedAgentId,
                        onClick = { onSelectAgent(agent.id) },
                        label = { Text(agent.name) },
                    )
                }
            }
        }
    }
    when {
        log.isLoading -> item { SkeletonList(rows = 3, modifier = Modifier.fillMaxWidth()) }
        log.error != null -> item {
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp)) { ErrorState(log.error, onRetry = onRetry) }
        }
        log.showEmpty -> item {
            Text(stringResource(R.string.activity_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            items(log.entries, key = { "log-" + it.id }) { entry ->
                LogRow(entry, expanded = log.expandedId == entry.id, onClick = { onToggle(entry.id) })
            }
            if (!log.endReached && log.entries.isNotEmpty()) {
                item {
                    TextButton(onClick = onLoadMore, enabled = !log.isLoadingMore, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.activity_log_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ToolInfo.label(entry.tool), style = MaterialTheme.typography.titleMedium)
            Text(entry.resultSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entry.createdAt?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (expanded && entry.argsRedacted != null) {
                HorizontalDivider()
                Text(stringResource(R.string.activity_log_args), style = MaterialTheme.typography.labelMedium)
                ArgsFormatter.format(entry.argsRedacted).forEach {
                    Text("${it.label}: ${it.value}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
