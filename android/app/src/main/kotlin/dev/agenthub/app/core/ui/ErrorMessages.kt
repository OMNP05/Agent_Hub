package dev.agenthub.app.core.ui

import androidx.annotation.StringRes
import dev.agenthub.app.R
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.voice.VoiceError

/** User-facing text for an error; raw backend codes and bodies are never shown. */
@StringRes
fun VoiceError.messageRes(): Int = when (this) {
    VoiceError.Unavailable -> R.string.voice_error_unavailable
    VoiceError.PermissionDenied -> R.string.voice_error_permission
    VoiceError.NoSpeech -> R.string.voice_error_no_speech
    VoiceError.Network -> R.string.voice_error_network
    VoiceError.Busy -> R.string.voice_error_busy
    VoiceError.Failed -> R.string.voice_error_failed
}

@StringRes
fun DataError.messageRes(): Int = when (this) {
    DataError.Network -> R.string.error_network
    DataError.Unauthorized -> R.string.error_unauthorized
    DataError.AgentBusy -> R.string.error_agent_busy
    DataError.LlmKeyInvalid -> R.string.error_llm_key_invalid
    DataError.ConnectionNeedsReauth -> R.string.error_connection_needs_reauth
    DataError.ApprovalExpired -> R.string.error_approval_expired
    DataError.SignInNotConfigured -> R.string.error_sign_in_not_configured
    DataError.NoGoogleAccount -> R.string.error_no_google_account
    is DataError.Backend, DataError.Unknown -> R.string.error_generic
}
