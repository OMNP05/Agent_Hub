package dev.agenthub.app.agents.catalog

import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.RiskLevel

data class ToolDef(val id: String, val label: String, val risk: RiskLevel)

/** One switch shown to the user. It enables [tools]; its risk is the highest risk among them. */
data class ToggleDef(
    val id: String,
    val label: String,
    val description: String,
    val defaultOn: Boolean,
    val tools: List<ToolDef>,
) {
    val risk: RiskLevel get() = tools.maxOf { it.risk }
}

data class AgentTemplate(
    val id: String,
    val displayName: String,
    val tagline: String,
    val connector: ConnectorType,
    val defaultInstructions: String,
    val toggles: List<ToggleDef>,
) {
    val tools: List<ToolDef> get() = toggles.flatMap { it.tools }
}
