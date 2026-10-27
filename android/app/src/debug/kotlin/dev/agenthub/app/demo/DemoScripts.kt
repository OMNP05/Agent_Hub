package dev.agenthub.app.demo

import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.core.model.ConnectorType
import dev.agenthub.app.core.model.RiskLevel
import dev.agenthub.app.core.network.dto.AgentDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the demo agent decided to do with a tool. [summary] is the trimmed text that goes into history. */
internal data class Intent(val tool: String, val args: JsonObject, val summary: String, val answer: String)

/** The demo agent's decision for one message. Pure, so every rule can be unit tested. */
internal sealed interface Plan {
    data class Answer(val text: String) : Plan
    data class Run(val intent: Intent) : Plan
    data class NeedsApproval(val intent: Intent, val preface: String) : Plan

    /** The tool exists but its permission toggle is off, so the agent may not use it (default deny). */
    data class Denied(val tool: String, val toggleLabel: String) : Plan
}

/**
 * Canned behaviour for the demo backend. It applies the same permission rules as architecture 2.1:
 * a tool must be switched on, Write tools follow Ask or Allow, and Dangerous tools always ask.
 * All data in the answers is made up.
 */
internal object DemoScripts {
    const val PREFACE = "I can do that, but it needs your approval first."

    fun plan(agent: AgentDto, message: String): Plan {
        val template = TemplateCatalog.byId(agent.templateId) ?: return Plan.Answer("This agent type is not available.")
        val intent = intentFor(template.connector, message) ?: return Plan.Answer(help(template.connector))

        val toggle = template.toggles.firstOrNull { t -> t.tools.any { it.id == intent.tool } }
        if (toggle == null || agent.toggles[toggle.id] != true) return Plan.Denied(intent.tool, toggle?.label ?: intent.tool)

        val risk = template.tools.first { it.id == intent.tool }.risk
        val mode = agent.approvalModes[intent.tool]
        return when {
            risk == RiskLevel.Read -> Plan.Run(intent)
            risk == RiskLevel.Write && mode == "allow" -> Plan.Run(intent)
            // Dangerous tools can never be set to allow, whatever the stored mode says.
            else -> Plan.NeedsApproval(intent, PREFACE)
        }
    }

    fun deniedText(label: String) =
        "I can't do that: the \"$label\" permission is switched off for this agent. You can turn it on from the agent's screen."

    fun rejectedText(label: String) = "Okay, I did not run \"$label\". Nothing was changed."

    private fun intentFor(connector: ConnectorType, message: String): Intent? {
        val t = message.lowercase()
        return when (connector) {
            ConnectorType.Github -> github(message, t)
            ConnectorType.Gmail -> gmail(message, t)
            ConnectorType.Calendar -> calendar(message, t)
        }
    }

    private fun github(message: String, t: String): Intent? = when {
        t.hasAny("bio", "profile", "name") && t.hasAny("change", "set", "update", "edit", "make") ->
            Intent(
                "github.update_profile",
                buildJsonObject { put("bio", afterTo(message) ?: "Android developer") },
                "Updated your profile",
                "Done. Your GitHub profile now has the new bio.",
            )
        t.hasAny("unfollow") -> Intent("github.unfollow_user", buildJsonObject { put("username", "octocat") }, "Unfollowed octocat", "Done. You no longer follow octocat.")
        t.hasAny("follow ") && !t.hasAny("followers", "following") ->
            Intent("github.follow_user", buildJsonObject { put("username", "octocat") }, "Followed octocat", "Done. You now follow octocat.")
        t.hasAny("review") -> Intent(
            "github.list_review_requests", JsonObject(emptyMap()), "Fetched 2 PRs waiting for your review",
            "Two pull requests are waiting for your review:\n1. Fix flaky login test (#412)\n2. Add dark theme tokens (#418)",
        )
        t.hasAny("following") -> Intent("github.list_following", JsonObject(emptyMap()), "Fetched 16 accounts you follow", "You follow 16 accounts. The most recent is octocat.")
        t.hasAny("follower") -> Intent("github.list_followers", JsonObject(emptyMap()), "Fetched 12 followers", "You have 12 followers. Your newest follower is demo-user-7.")
        t.hasAny("pr", "pull") -> Intent(
            "github.list_my_pull_requests", JsonObject(emptyMap()), "Fetched 3 open PRs",
            "You have 3 open pull requests:\n1. Add retry to the sync worker (#431)\n2. Update README setup steps (#429)\n3. Bump Compose BOM (#427)",
        )
        t.hasAny("profile", "bio") -> Intent("github.get_profile", JsonObject(emptyMap()), "Fetched your profile", "Your profile: Demo Developer, bio \"Android developer\", Bengaluru.")
        else -> null
    }

    private fun gmail(message: String, t: String): Intent? {
        val to = EMAIL.find(message)?.value ?: "sam@example.com"
        return when {
            t.hasAny("send") -> Intent(
                "gmail.send_email",
                buildJsonObject {
                    putJsonArray("to") { add(JsonPrimitive(to)) }
                    put("subject", "Quick note")
                    put("body", afterKeyword(message, "saying", "that", ":") ?: "Hi,\n\nSharing the update we discussed.\n\nThanks")
                },
                "Sent 1 email", "Done. I sent the email to $to.",
            )
            t.hasAny("draft") -> Intent(
                "gmail.create_draft",
                buildJsonObject {
                    putJsonArray("to") { add(JsonPrimitive(to)) }
                    put("subject", "Re: your message")
                    put("body", afterKeyword(message, "saying", "that", ":") ?: "Thanks, I will join at 5.")
                },
                "Saved 1 draft", "Done. A draft is saved in Gmail. Nothing was sent.",
            )
            t.hasAny("archive") -> Intent("gmail.archive_email", buildJsonObject { put("message_id", "demo-msg-12") }, "Archived 1 email", "Done. I archived the newsletter.")
            t.hasAny("unread", "inbox", "mail", "summar") -> Intent(
                "gmail.search_emails", JsonObject(emptyMap()), "Found 4 unread emails",
                "You have 4 unread emails:\n1. Priya Nair: Design review notes\n2. Billing: Your invoice is ready\n3. Jobs digest: 6 new roles\n4. Sam Rao: Weekly report",
            )
            else -> null
        }
    }

    private fun calendar(message: String, t: String): Intent? = when {
        t.hasAny("delete", "cancel") -> Intent(
            "calendar.delete_event", buildJsonObject { put("event_id", "demo-evt-2"); put("title", "Team sync") },
            "Deleted 1 event", "Done. I deleted \"Team sync\".",
        )
        t.hasAny("invite") -> Intent(
            "calendar.create_event_with_attendees",
            buildJsonObject {
                put("title", eventTitle(message))
                put("start", "Tomorrow 16:00 IST")
                putJsonArray("attendees") { add(JsonPrimitive(EMAIL.find(message)?.value ?: "sam@example.com")) }
            },
            "Created 1 event with 1 guest", "Done. The invitation is sent.",
        )
        t.hasAny("add", "create", "book") -> Intent(
            "calendar.create_event",
            buildJsonObject { put("title", eventTitle(message)); put("start", "Tomorrow 16:00 IST"); put("duration_minutes", 30) },
            "Created 1 event", "Done. \"${eventTitle(message)}\" is on your calendar for tomorrow at 4 pm.",
        )
        t.hasAny("free") -> Intent("calendar.find_free_slots", JsonObject(emptyMap()), "Found 3 free slots", "You are free at 10:00, 13:30 and 16:00 tomorrow.")
        t.hasAny("today", "tomorrow", "calendar", "agenda", "meeting", "schedule") -> Intent(
            "calendar.list_events", JsonObject(emptyMap()), "Found 3 events",
            "Tomorrow you have 3 events:\n1. 09:30 Stand-up\n2. 12:00 Lunch with Priya\n3. 15:00 Team sync",
        )
        else -> null
    }

    private fun help(connector: ConnectorType) = when (connector) {
        ConnectorType.Github -> "I can show your pull requests, review requests and followers, or change your bio. Try \"Show my open PRs\" or \"Change my bio to: Android developer\"."
        ConnectorType.Gmail -> "I can find unread mail, draft replies and send email with your approval. Try \"Any unread mail?\" or \"Send an email to sam@example.com saying I'll join at 5\"."
        ConnectorType.Calendar -> "I can show your schedule, find free time and add events. Try \"What's on my calendar tomorrow?\" or \"Add dentist tomorrow\"."
    }

    private fun String.hasAny(vararg words: String) = words.any { contains(it) }

    private fun afterTo(message: String): String? = TO.find(message)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun afterKeyword(message: String, vararg keywords: String): String? {
        for (k in keywords) {
            val i = message.indexOf(k, ignoreCase = true)
            if (i >= 0) message.substring(i + k.length).trim().trimStart(':').trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun eventTitle(message: String): String =
        TITLE.find(message)?.groupValues?.get(1)?.trim()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() } ?: "New event"

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val TO = Regex("""(?i)\bto\b[:\s]+(.+)$""")
    private val TITLE = Regex("""(?i)(?:add|create|book)\s+(?:a\s+|an\s+)?(.+?)(?:\s+(?:at|on|for|tomorrow|today|with)\b|$)""")
}
