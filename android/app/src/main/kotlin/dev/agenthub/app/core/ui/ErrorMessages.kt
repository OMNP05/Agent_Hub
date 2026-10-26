package dev.agenthub.app.core.ui

import androidx.annotation.StringRes
import dev.agenthub.app.R
import dev.agenthub.app.core.error.DataError

/** User-facing text for an error; raw backend codes and bodies are never shown. */
@StringRes
fun DataError.messageRes(): Int = when (this) {
    DataError.Network -> R.string.error_network
    DataError.Unauthorized -> R.string.error_unauthorized
    DataError.AgentBusy -> R.string.error_agent_busy
    DataError.LlmKeyInvalid -> R.string.error_llm_key_invalid
    DataError.ConnectionNeedsReauth -> R.string.error_connection_needs_reauth
    DataError.ApprovalExpired -> R.string.error_approval_expired
    DataError.SignInNotConfigured -> R.string.error_sign_in_not_configured
    is DataError.Backend, DataError.Unknown -> R.string.error_generic
}
