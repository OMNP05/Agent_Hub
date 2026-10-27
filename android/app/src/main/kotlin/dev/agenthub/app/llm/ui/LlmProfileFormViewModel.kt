package dev.agenthub.app.llm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.llm.data.FieldError
import dev.agenthub.app.llm.data.LlmProfile
import dev.agenthub.app.llm.data.LlmProfileInput
import dev.agenthub.app.llm.data.LlmProfileValidator
import dev.agenthub.app.llm.data.LlmProvider
import dev.agenthub.app.llm.data.LlmRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editable form. [apiKey] is typed text held only in memory: it is never saved to disk or to saved state, is
 * cleared after a successful save, and is not shown again. The stored key is represented only by [keyLast4].
 */
data class LlmFormUiState(
    val profileId: String? = null,
    val name: String = "",
    val provider: LlmProvider = LlmProvider.OpenAi,
    val baseUrl: String = "",
    val model: String = "",
    val temperature: String = "0.2",
    val maxTokens: String = "1000",
    val apiKey: String = "",
    val keyLast4: String? = null,
    val makeDefault: Boolean = false,
    val errors: Map<String, FieldError> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val testState: TestState = TestState.Idle,
    val error: DataError? = null,
    val notFound: Boolean = false,
) {
    val isNew: Boolean get() = profileId == null
    val hasStoredKey: Boolean get() = !keyLast4.isNullOrBlank()

    override fun toString(): String = "LlmFormUiState(name=$name, provider=$provider, model=$model, apiKey=***)"
}

sealed interface LlmFormEffect { data object Saved : LlmFormEffect }

@HiltViewModel
class LlmProfileFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LlmRepository,
) : ViewModel() {

    private val editId: String? = savedStateHandle.get<String>("profileId")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(LlmFormUiState(profileId = editId, isLoading = editId != null))
    val uiState: StateFlow<LlmFormUiState> = _uiState.asStateFlow()

    private val _effects = Channel<LlmFormEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        if (editId != null) loadExisting(editId) else applyProviderDefaults(LlmProvider.OpenAi)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            when (val result = repository.profiles()) {
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error) }
                is AppResult.Success -> {
                    val profile = result.value.firstOrNull { it.id == id }
                    if (profile == null) _uiState.update { it.copy(isLoading = false, notFound = true) } else fill(profile)
                }
            }
        }
    }

    private fun fill(p: LlmProfile) = _uiState.update {
        it.copy(
            name = p.name, provider = p.provider, baseUrl = p.baseUrl.orEmpty(), model = p.model,
            temperature = p.temperature.toString(), maxTokens = p.maxTokens.toString(),
            keyLast4 = p.keyLast4, makeDefault = p.isDefault, isLoading = false, apiKey = "",
        )
    }

    fun onName(v: String) = change { it.copy(name = v.take(LlmProfileValidator.MAX_NAME + 20), errors = it.errors - "name") }
    fun onModel(v: String) = change { it.copy(model = v, errors = it.errors - "model") }
    fun onBaseUrl(v: String) = change { it.copy(baseUrl = v, errors = it.errors - "baseUrl") }
    fun onTemperature(v: String) = change { it.copy(temperature = v, errors = it.errors - "temperature") }
    fun onMaxTokens(v: String) = change { it.copy(maxTokens = v, errors = it.errors - "maxTokens") }
    fun onApiKey(v: String) = change { it.copy(apiKey = v.trim(), errors = it.errors - "apiKey") }
    fun onDefault(v: Boolean) = change { it.copy(makeDefault = v) }

    fun onProvider(provider: LlmProvider) {
        if (provider == _uiState.value.provider) return
        applyProviderDefaults(provider)
    }

    // Official providers show their own address as a hint and leave the field empty; custom endpoints must type one.
    private fun applyProviderDefaults(provider: LlmProvider) = change {
        it.copy(provider = provider, baseUrl = if (provider.requiresBaseUrl) it.baseUrl else "", errors = it.errors - "baseUrl")
    }

    private fun change(block: (LlmFormUiState) -> LlmFormUiState) = _uiState.update { block(it).copy(testState = TestState.Idle, error = null) }

    private fun buildInput(state: LlmFormUiState): LlmProfileInput? {
        val temperature = LlmProfileValidator.parseTemperature(state.temperature)
        val maxTokens = LlmProfileValidator.parseMaxTokens(state.maxTokens)
        val errors = linkedMapOf<String, FieldError>()
        if (temperature == null) errors["temperature"] = FieldError.TemperatureRange
        if (maxTokens == null) errors["maxTokens"] = FieldError.MaxTokensRange
        val input = LlmProfileInput(
            state.name, state.provider, state.baseUrl, state.model,
            temperature ?: 0.0, maxTokens ?: 0, state.apiKey, state.makeDefault,
        )
        errors += LlmProfileValidator.validate(input, isNew = state.isNew).filterKeys { it !in errors }
        _uiState.update { it.copy(errors = errors) }
        return if (errors.isEmpty()) input else null
    }

    fun onSave() {
        val state = _uiState.value
        if (state.isSaving) return
        val input = buildInput(state) ?: return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val result = if (state.profileId == null) repository.create(input) else repository.update(state.profileId, input)
            when (result) {
                is AppResult.Success -> {
                    // The key was sent once; drop it from memory and never show it again.
                    _uiState.update { it.copy(isSaving = false, apiKey = "", keyLast4 = result.value.keyLast4) }
                    _effects.trySend(LlmFormEffect.Saved)
                }
                is AppResult.Failure -> _uiState.update { it.copy(isSaving = false, error = result.error) }
            }
        }
    }

    /** Tests what is on screen. A new profile needs its key typed; an existing one can test the stored key. */
    fun onTest() {
        val state = _uiState.value
        if (state.testState == TestState.Running) return
        val needsDraft = state.isNew || state.apiKey.isNotBlank()
        val input = buildInput(state) ?: return
        _uiState.update { it.copy(testState = TestState.Running, error = null) }
        viewModelScope.launch {
            val result = if (needsDraft) repository.testDraft(input) else repository.test(requireNotNull(state.profileId))
            val next = when (result) {
                is AppResult.Success -> if (result.value.ok) TestState.Passed else TestState.Failed
                is AppResult.Failure -> TestState.Failed
            }
            _uiState.update { it.copy(testState = next, error = (result as? AppResult.Failure)?.error?.takeIf { e -> e == DataError.LlmKeyInvalid || e == DataError.Network }) }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }
}
