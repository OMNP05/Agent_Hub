package dev.agenthub.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSpeechEngine(var available: Boolean = true) : SpeechEngine {
    var script: () -> Flow<SpeechEvent> = { flow { } }
    var listenedWith: String? = null
    var listenCalls = 0
    override fun isAvailable() = available
    override fun listen(languageTag: String): Flow<SpeechEvent> { listenCalls++; listenedWith = languageTag; return script() }
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<Pair<String, String>>()
    var stops = 0
    override fun speak(text: String, languageTag: String) { spoken += text to languageTag }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val engine = FakeSpeechEngine()
    private val speaker = FakeSpeaker()
    private fun vm() = VoiceViewModel(engine, speaker)

    @Test
    fun finalTranscriptIsEmittedAndListeningStops() = runTest {
        engine.script = { flow { emit(SpeechEvent.Partial("show my")); emit(SpeechEvent.Final("show my open PRs")) } }
        val v = vm()

        v.onMicTap()

        assertEquals(VoiceEffect.Transcript("show my open PRs"), v.effects.first())
        assertFalse(v.uiState.value.isListening)
        assertEquals("", v.uiState.value.partial)
    }

    @Test
    fun partialWordsAreShownWhileListening() = runTest {
        engine.script = { flow { emit(SpeechEvent.Partial("show my")); awaitCancellation() } }
        val v = vm()

        v.onMicTap()

        assertTrue(v.uiState.value.isListening)
        assertEquals("show my", v.uiState.value.partial)
        v.stopListening()
        assertFalse(v.uiState.value.isListening)
    }

    @Test
    fun tappingTheMicWhileListeningStopsIt() = runTest {
        engine.script = { flow { awaitCancellation() } }
        val v = vm()
        v.onMicTap()
        assertTrue(v.uiState.value.isListening)

        v.onMicTap()

        assertFalse(v.uiState.value.isListening)
        assertEquals(1, engine.listenCalls)
    }

    @Test
    fun languageSelectionIsPassedToTheEngine() {
        val v = vm()
        v.onLanguageToggle()
        assertEquals(VoiceLanguage.Hindi, v.uiState.value.language)
        v.onMicTap()
        assertEquals("hi-IN", engine.listenedWith)
        v.stopListening()
    }

    @Test
    fun languageCannotChangeWhileListening() = runTest {
        engine.script = { flow { awaitCancellation() } }
        val v = vm()
        v.onMicTap()
        v.onLanguageToggle()
        assertEquals(VoiceLanguage.English, v.uiState.value.language)
        v.stopListening()
    }

    @Test
    fun unavailableRecognizerReportsAnErrorWithoutListening() {
        engine.available = false
        val v = vm()
        v.onMicTap()
        assertEquals(VoiceError.Unavailable, v.uiState.value.error)
        assertFalse(v.uiState.value.isListening)
        assertEquals(0, engine.listenCalls)
    }

    @Test
    fun engineFailureIsShownAndListeningEnds() {
        engine.script = { flow { emit(SpeechEvent.Failure(VoiceError.NoSpeech)) } }
        val v = vm()
        v.onMicTap()
        assertEquals(VoiceError.NoSpeech, v.uiState.value.error)
        assertFalse(v.uiState.value.isListening)
    }

    @Test
    fun anUnexpectedExceptionBecomesAGenericVoiceError() {
        engine.script = { flow { throw IllegalStateException("recognizer crashed") } }
        val v = vm()
        v.onMicTap()
        assertEquals(VoiceError.Failed, v.uiState.value.error)
        assertFalse(v.uiState.value.isListening)
    }

    @Test
    fun deniedPermissionIsReportedAndErrorCanBeDismissed() {
        val v = vm()
        v.onPermissionDenied()
        assertEquals(VoiceError.PermissionDenied, v.uiState.value.error)
        v.onErrorShown()
        assertNull(v.uiState.value.error)
    }

    @Test
    fun startingToListenSilencesAnyReplyBeingRead() {
        val v = vm()
        v.onMicTap()
        assertEquals(1, speaker.stops)
        v.stopListening()
    }

    @Test
    fun repliesAreSpokenAsShortSummariesInTheSelectedLanguage() {
        val v = vm()
        v.onLanguageToggle()
        v.speak("You have **3 open PRs**. See [details](https://example.com/x).")
        assertEquals(listOf("You have 3 open PRs. See details." to "hi-IN"), speaker.spoken)
    }

    @Test
    fun emptyRepliesAreNotSpoken() {
        val v = vm()
        v.speak("   ")
        v.speak("https://example.com/only-a-link")
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun leavingTheScreenStopsSpeaking() {
        val v = vm()
        v.stopSpeaking()
        assertEquals(1, speaker.stops)
    }
}
