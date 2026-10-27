package dev.agenthub.app.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceUiState(
    val isListening: Boolean = false,
    /** Live words while the user is still speaking. Never sent anywhere. */
    val partial: String = "",
    val error: VoiceError? = null,
    val language: VoiceLanguage = VoiceLanguage.English,
)

sealed interface VoiceEffect {
    /** Final recognised text. The chat puts it in the message box for the user to read, edit and send. */
    data class Transcript(val text: String) : VoiceEffect
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val engine: SpeechEngine,
    private val speaker: Speaker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _effects = Channel<VoiceEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var job: Job? = null

    /** Starts listening, or stops if already listening. The caller must hold the microphone permission. */
    fun onMicTap() {
        if (_uiState.value.isListening) return stopListening()
        speaker.stop()
        if (!engine.isAvailable()) {
            _uiState.update { it.copy(error = VoiceError.Unavailable) }
            return
        }
        _uiState.update { it.copy(isListening = true, partial = "", error = null) }
        job = viewModelScope.launch {
            try {
                engine.listen(_uiState.value.language.tag).collect { event ->
                    when (event) {
                        is SpeechEvent.Partial -> _uiState.update { it.copy(partial = event.text) }
                        is SpeechEvent.Final -> _effects.trySend(VoiceEffect.Transcript(event.text))
                        is SpeechEvent.Failure -> _uiState.update { it.copy(error = event.error) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = VoiceError.Failed) }
            } finally {
                _uiState.update { it.copy(isListening = false, partial = "") }
            }
        }
    }

    fun stopListening() {
        job?.cancel()
        job = null
    }

    fun onPermissionDenied() = _uiState.update { it.copy(error = VoiceError.PermissionDenied, isListening = false) }

    fun onLanguageToggle() {
        if (_uiState.value.isListening) return
        _uiState.update { it.copy(language = it.language.next(), error = null) }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    /** Reads a short summary of the reply aloud. Only called for requests the user started by voice. */
    fun speak(reply: String) {
        val summary = SpokenSummary.shorten(reply)
        if (summary.isNotEmpty()) speaker.speak(summary, _uiState.value.language.tag)
    }

    fun stopSpeaking() = speaker.stop()

    override fun onCleared() {
        speaker.stop()
        super.onCleared()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds abstract fun speechEngine(impl: AndroidSpeechEngine): SpeechEngine
    @Binds abstract fun speaker(impl: AndroidSpeaker): Speaker
}
