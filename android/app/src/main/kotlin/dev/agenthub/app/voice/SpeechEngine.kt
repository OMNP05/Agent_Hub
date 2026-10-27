package dev.agenthub.app.voice

import kotlinx.coroutines.flow.Flow

/** Recognition languages offered in v1 (blueprint 12): English (India) and Hindi. */
enum class VoiceLanguage(val tag: String, val label: String) {
    English("en-IN", "EN"),
    Hindi("hi-IN", "हिं");

    fun next(): VoiceLanguage = entries[(ordinal + 1) % entries.size]
}

enum class VoiceError { Unavailable, PermissionDenied, NoSpeech, Network, Busy, Failed }

sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Failure(val error: VoiceError) : SpeechEvent
}

/**
 * Speech to text. The voice package may only turn speech into a draft message. It must never
 * depend on the approvals code: voice can start a request but can never approve one (CLAUDE.md rule 3).
 */
interface SpeechEngine {
    fun isAvailable(): Boolean

    /** Starts listening. Collect to run; cancelling the collector stops and releases the recognizer. */
    fun listen(languageTag: String): Flow<SpeechEvent>
}

object SpeechErrors {
    // Values of android.speech.SpeechRecognizer.ERROR_*; kept here so the mapping is unit testable.
    fun fromCode(code: Int): VoiceError = when (code) {
        1, 2, 4, 11 -> VoiceError.Network
        6, 7 -> VoiceError.NoSpeech
        8, 10 -> VoiceError.Busy
        9 -> VoiceError.PermissionDenied
        12, 13 -> VoiceError.Unavailable
        else -> VoiceError.Failed
    }
}
