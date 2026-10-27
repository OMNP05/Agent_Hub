package dev.agenthub.app.push

/** What a push needs to show. Deliberately contains no tool arguments, so nothing sensitive reaches the lock screen. */
data class ApprovalPush(val approvalId: String, val agentName: String)

object PushPayload {
    private const val MAX_NAME = 60
    private const val MAX_ID = 100

    /** Returns null for anything that is not a well-formed approval push. */
    fun parse(data: Map<String, String>): ApprovalPush? {
        if (data["type"] != "approval") return null
        val id = data["approval_id"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
        val name = data["agent_name"].orEmpty().filterNot { it.isISOControl() }.trim().take(MAX_NAME)
        return ApprovalPush(id, name)
    }
}
