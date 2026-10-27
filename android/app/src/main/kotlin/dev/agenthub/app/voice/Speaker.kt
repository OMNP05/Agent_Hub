package dev.agenthub.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Text to speech for short spoken replies. */
interface Speaker {
    fun speak(text: String, languageTag: String)
    fun stop()
}

@Singleton
class AndroidSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) : Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    @Synchronized
    override fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        ensureEngine()
        if (ready) say(text, languageTag) else pending = text to languageTag
    }

    @Synchronized
    override fun stop() {
        pending = null
        tts?.stop()
    }

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                val next = pending
                pending = null
                if (ready && next != null) say(next.first, next.second)
            }
        }
    }

    private fun say(text: String, languageTag: String) {
        val engine = tts ?: return
        // If the language voice is missing the engine keeps its default voice rather than failing silently.
        engine.setLanguage(Locale.forLanguageTag(languageTag))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
}
