package dev.agenthub.app.approvals.ui

import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.core.model.RiskLevel

/** Display helpers for a tool id such as `gmail.send_email`. */
object ToolInfo {
    private val byId = TemplateCatalog.all.flatMap { it.tools }.associateBy { it.id }

    fun label(toolId: String): String = byId[toolId]?.label ?: toolId

    /** A tool the catalog does not know is shown as Dangerous: the safest assumption for something unexpected. */
    fun risk(toolId: String): RiskLevel = byId[toolId]?.risk ?: RiskLevel.Dangerous
}
