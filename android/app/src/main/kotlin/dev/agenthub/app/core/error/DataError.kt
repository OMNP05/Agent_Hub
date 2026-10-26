package dev.agenthub.app.core.error

/**
 * Error vocabulary shared by every repository. Platform and HTTP exceptions never leave the data layer;
 * backend error codes (CLAUDE.md "Conventions") map to these cases, and raw bodies are never exposed.
 */
sealed interface DataError {
    data object Network : DataError
    data object Unauthorized : DataError
    data object AgentBusy : DataError
    data object LlmKeyInvalid : DataError
    data object ConnectionNeedsReauth : DataError
    data object ApprovalExpired : DataError
    data object SignInNotConfigured : DataError
    data class Backend(val code: String) : DataError
    data object Unknown : DataError

    companion object {
        fun fromCode(code: String?): DataError = when (code) {
            "llm_key_invalid" -> LlmKeyInvalid
            "connection_needs_reauth" -> ConnectionNeedsReauth
            "approval_expired" -> ApprovalExpired
            "agent_busy" -> AgentBusy
            "unauthorized" -> Unauthorized
            null, "" -> Unknown
            else -> Backend(code)
        }
    }
}
