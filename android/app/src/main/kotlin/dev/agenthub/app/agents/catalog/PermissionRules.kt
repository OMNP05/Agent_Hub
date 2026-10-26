package dev.agenthub.app.agents.catalog

import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.RiskLevel

/** Client-side mirror of the approval rules in architecture 2.1. The backend Gateway enforces them for real. */
object PermissionRules {

    /** Read tools run without approval, Write tools may be Ask or Allow, Dangerous tools can only Ask. */
    fun allowedModes(risk: RiskLevel): List<ApprovalMode> = when (risk) {
        RiskLevel.Read -> emptyList()
        RiskLevel.Write -> listOf(ApprovalMode.Ask, ApprovalMode.Allow)
        RiskLevel.Dangerous -> listOf(ApprovalMode.Ask)
    }

    fun defaults(template: AgentTemplate): AgentPermissions = sanitize(template, AgentPermissions())

    /**
     * Returns permissions that are valid for [template]: unknown toggles and tools are dropped, missing
     * toggles take their default, and every Write or Dangerous tool has a mode that its risk level allows.
     */
    fun sanitize(template: AgentTemplate, permissions: AgentPermissions): AgentPermissions {
        val toggles = template.toggles.associate { it.id to (permissions.toggles[it.id] ?: it.defaultOn) }
        val modes = template.tools
            .filter { allowedModes(it.risk).isNotEmpty() }
            .associate { tool ->
                val wanted = permissions.approvalModes[tool.id] ?: ApprovalMode.Ask
                tool.id to if (wanted in allowedModes(tool.risk)) wanted else ApprovalMode.Ask
            }
        return AgentPermissions(toggles, modes)
    }

    /** The approval mode shown for a toggle: Allow only if every Write tool in it is set to Allow. */
    fun toggleMode(template: AgentTemplate, permissions: AgentPermissions, toggleId: String): ApprovalMode {
        val writes = template.toggles.firstOrNull { it.id == toggleId }?.tools.orEmpty()
            .filter { it.risk == RiskLevel.Write }
        if (writes.isEmpty()) return ApprovalMode.Ask
        return if (writes.all { permissions.approvalModes[it.id] == ApprovalMode.Allow }) ApprovalMode.Allow else ApprovalMode.Ask
    }

    /** Sets the mode of every Write tool in a toggle. Dangerous and Read tools are never changed. */
    fun withToggleMode(
        template: AgentTemplate,
        permissions: AgentPermissions,
        toggleId: String,
        mode: ApprovalMode,
    ): AgentPermissions {
        val writes = template.toggles.firstOrNull { it.id == toggleId }?.tools.orEmpty()
            .filter { it.risk == RiskLevel.Write }
        val modes = permissions.approvalModes + writes.associate { it.id to mode }
        return sanitize(template, permissions.copy(approvalModes = modes))
    }

    /** Toggles that are on now but were off before, i.e. permissions the connection may not cover yet. */
    fun newlyEnabled(before: AgentPermissions, after: AgentPermissions): Set<String> =
        after.toggles.filter { (id, on) -> on && before.toggles[id] != true }.keys
}
