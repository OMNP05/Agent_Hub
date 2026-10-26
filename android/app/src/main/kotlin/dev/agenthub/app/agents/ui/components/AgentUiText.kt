package dev.agenthub.app.agents.ui.components

import androidx.annotation.StringRes
import dev.agenthub.app.R
import dev.agenthub.app.core.model.Agent
import dev.agenthub.app.core.model.AgentStatus
import dev.agenthub.app.core.model.ConnectionStatus

@StringRes
fun Agent.statusLabelRes(): Int = when {
    connection == null -> R.string.agent_needs_connection
    connection.status != ConnectionStatus.Active -> R.string.agent_needs_reauth
    status == AgentStatus.Active -> R.string.agent_status_active
    status == AgentStatus.Draft -> R.string.agent_status_draft
    else -> R.string.agent_status_paused
}
