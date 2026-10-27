package dev.agenthub.app.demo

import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.core.network.dto.AgentDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScriptsTest {
    private fun agent(templateId: String, vararg on: String, modes: Map<String, String> = emptyMap()): AgentDto {
        val template = TemplateCatalog.byId(templateId)!!
        val defaults = PermissionRules.defaults(template)
        val toggles = defaults.toggles + on.associateWith { true }
        return AgentDto(
            "a", "A", templateId, template.connector.wire, status = "active", toggles = toggles,
            approvalModes = defaults.approvalModes.mapValues { it.value.wire } + modes,
        )
    }

    @Test
    fun readToolsRunWithoutAsking() {
        val plan = DemoScripts.plan(agent("github-assistant"), "Show my open PRs")
        assertTrue(plan is Plan.Run)
        assertEquals("github.list_my_pull_requests", (plan as Plan.Run).intent.tool)
    }

    @Test
    fun writeToolsAskByDefaultAndRunOnlyWhenSetToAllow() {
        val on = agent("github-assistant", "edit_profile")
        assertTrue(DemoScripts.plan(on, "Change my bio to: hello") is Plan.NeedsApproval)
        val allow = agent("github-assistant", "edit_profile", modes = mapOf("github.update_profile" to "allow"))
        assertTrue(DemoScripts.plan(allow, "Change my bio to: hello") is Plan.Run)
    }

    @Test
    fun dangerousToolsAlwaysAskEvenIfTheStoredModeSaysAllow() {
        val a = agent("gmail-assistant", "send_email", modes = mapOf("gmail.send_email" to "allow"))
        assertTrue(DemoScripts.plan(a, "Send an email to sam@example.com saying hi") is Plan.NeedsApproval)
        val c = agent("calendar-assistant", "delete_events", modes = mapOf("calendar.delete_event" to "allow"))
        assertTrue(DemoScripts.plan(c, "Delete my team sync") is Plan.NeedsApproval)
    }

    @Test
    fun aToolWithItsToggleOffIsDeniedNotRun() {
        val plan = DemoScripts.plan(agent("gmail-assistant"), "Send an email to sam@example.com saying hi")
        assertTrue(plan is Plan.Denied)
        assertEquals("Send email", (plan as Plan.Denied).toggleLabel)
    }

    @Test
    fun theBioIsTakenFromTheMessage() {
        val plan = DemoScripts.plan(agent("github-assistant", "edit_profile"), "Change my bio to: Rust fan") as Plan.NeedsApproval
        assertEquals("\"Rust fan\"", plan.intent.args["bio"].toString())
    }

    @Test
    fun theEmailRecipientAndBodyAreTakenFromTheMessage() {
        val plan = DemoScripts.plan(agent("gmail-assistant", "send_email"), "Send an email to lee@corp.example saying I'll join at 5") as Plan.NeedsApproval
        assertTrue(plan.intent.args["to"].toString().contains("lee@corp.example"))
        assertTrue(plan.intent.args["body"].toString().contains("I'll join at 5"))
    }

    @Test
    fun eachAgentOnlyKnowsItsOwnTools() {
        // A Gmail agent asked about pull requests has no such tool and just explains what it can do.
        val plan = DemoScripts.plan(agent("gmail-assistant"), "Show my open PRs")
        assertTrue(plan is Plan.Answer)
        assertTrue((plan as Plan.Answer).text.contains("email", ignoreCase = true))
    }

    @Test
    fun unknownTemplatesAreHandled() {
        val plan = DemoScripts.plan(AgentDto("a", "A", "custom", "github"), "hi")
        assertTrue(plan is Plan.Answer)
    }

    @Test
    fun calendarCreatePicksATitle() {
        val plan = DemoScripts.plan(agent("calendar-assistant", "edit_events"), "Add dentist tomorrow") as Plan.NeedsApproval
        assertEquals("\"Dentist\"", plan.intent.args["title"].toString())
    }
}
