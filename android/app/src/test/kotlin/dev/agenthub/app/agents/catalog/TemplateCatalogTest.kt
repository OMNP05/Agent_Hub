package dev.agenthub.app.agents.catalog

import dev.agenthub.app.core.model.AgentPermissions
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCatalogTest {

    @Test
    fun catalogHasExactlyTheThreeV1Agents() {
        assertEquals(
            setOf(ConnectorType.Github, ConnectorType.Gmail, ConnectorType.Calendar),
            TemplateCatalog.all.map { it.connector }.toSet(),
        )
        assertEquals(3, TemplateCatalog.all.size)
        assertEquals(
            listOf("github-assistant", "gmail-assistant", "calendar-assistant"),
            TemplateCatalog.all.map { it.id },
        )
    }

    @Test
    fun unknownTemplateIdsAreNotFound() {
        assertNull(TemplateCatalog.byId("custom"))
        assertNull(TemplateCatalog.byId(null))
    }

    @Test
    fun everyToolIdBelongsToItsTemplateConnector() {
        TemplateCatalog.all.forEach { template ->
            template.tools.forEach { assertTrue(it.id, it.id.startsWith(template.connector.wire + ".")) }
        }
    }

    @Test
    fun toolIdsAreUnique() {
        val ids = TemplateCatalog.all.flatMap { t -> t.tools.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun onlyReadTogglesAreOnByDefault() {
        TemplateCatalog.all.forEach { template ->
            template.toggles.filter { it.defaultOn }.forEach { assertEquals(it.id, RiskLevel.Read, it.risk) }
        }
    }

    @Test
    fun sendingAndDeletingAndInvitingAreDangerous() {
        fun risk(id: String) = TemplateCatalog.all.flatMap { it.tools }.first { it.id == id }.risk
        assertEquals(RiskLevel.Dangerous, risk("gmail.send_email"))
        assertEquals(RiskLevel.Dangerous, risk("calendar.delete_event"))
        assertEquals(RiskLevel.Dangerous, risk("calendar.create_event_with_attendees"))
        assertEquals(RiskLevel.Dangerous, risk("calendar.update_event_with_attendees"))
        assertEquals(RiskLevel.Write, risk("gmail.create_draft"))
        assertEquals(RiskLevel.Write, risk("github.update_profile"))
    }

    @Test
    fun dangerousToolsOfferNoAllowOption() {
        assertEquals(listOf(ApprovalMode.Ask), PermissionRules.allowedModes(RiskLevel.Dangerous))
        assertFalse(ApprovalMode.Allow in PermissionRules.allowedModes(RiskLevel.Dangerous))
        assertEquals(emptyList<ApprovalMode>(), PermissionRules.allowedModes(RiskLevel.Read))
    }

    @Test
    fun sanitizeForcesDangerousToolsBackToAsk() {
        val gmail = TemplateCatalog.byId("gmail-assistant")!!
        val tampered = AgentPermissions(
            toggles = mapOf("send_email" to true),
            approvalModes = mapOf("gmail.send_email" to ApprovalMode.Allow, "gmail.create_draft" to ApprovalMode.Allow),
        )
        val clean = PermissionRules.sanitize(gmail, tampered)
        assertEquals(ApprovalMode.Ask, clean.approvalModes["gmail.send_email"])
        assertEquals(ApprovalMode.Allow, clean.approvalModes["gmail.create_draft"])
    }

    @Test
    fun sanitizeDropsUnknownTogglesAndToolsAndFillsDefaults() {
        val github = TemplateCatalog.byId("github-assistant")!!
        val clean = PermissionRules.sanitize(
            github,
            AgentPermissions(
                toggles = mapOf("read_prs" to false, "hack" to true),
                approvalModes = mapOf("github.merge_pr" to ApprovalMode.Allow),
            ),
        )
        assertEquals(false, clean.toggles["read_prs"])
        assertEquals(true, clean.toggles["read_followers"])
        assertEquals(false, clean.toggles["edit_profile"])
        assertFalse("hack" in clean.toggles)
        assertFalse("github.merge_pr" in clean.approvalModes)
    }

    @Test
    fun writeToolsDefaultToAskAndReadToolsHaveNoMode() {
        val clean = PermissionRules.defaults(TemplateCatalog.byId("github-assistant")!!)
        assertEquals(ApprovalMode.Ask, clean.approvalModes["github.update_profile"])
        assertFalse("github.get_profile" in clean.approvalModes)
    }

    @Test
    fun newlyEnabledReportsOnlyTogglesTurnedOn() {
        val before = AgentPermissions(mapOf("a" to true, "b" to false, "c" to false))
        val after = AgentPermissions(mapOf("a" to true, "b" to true, "c" to false))
        assertEquals(setOf("b"), PermissionRules.newlyEnabled(before, after))
    }
}
