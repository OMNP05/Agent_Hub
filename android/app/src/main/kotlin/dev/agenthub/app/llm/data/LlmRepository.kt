package dev.agenthub.app.llm.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.error.map
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiCall
import dev.agenthub.app.core.network.apiUnitCall
import dev.agenthub.app.core.network.dto.LlmProfileDto
import dev.agenthub.app.core.network.dto.LlmProfileRequest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface LlmRepository {
    suspend fun profiles(): AppResult<List<LlmProfile>>
    suspend fun create(input: LlmProfileInput): AppResult<LlmProfile>

    /** An empty key in [input] keeps the key stored on the server. */
    suspend fun update(id: String, input: LlmProfileInput): AppResult<LlmProfile>
    suspend fun delete(id: String): AppResult<Unit>
    suspend fun test(id: String): AppResult<TestResult>

    /** Tests the values on screen before saving; the key is sent once and not kept by the app. */
    suspend fun testDraft(input: LlmProfileInput): AppResult<TestResult>
}

internal fun LlmProfileDto.toDomain(): LlmProfile? {
    val p = LlmProvider.fromWire(provider) ?: return null
    return LlmProfile(id, name, p, baseUrl?.takeIf { it.isNotBlank() }, model, temperature, maxTokens, keyLast4, isDefault)
}

internal fun LlmProfileInput.toRequest() = LlmProfileRequest(
    name = name.trim(),
    provider = provider.wire,
    baseUrl = baseUrl.trim().takeIf { it.isNotEmpty() },
    model = model.trim(),
    temperature = temperature,
    maxTokens = maxTokens,
    // Null means "unchanged" and is omitted from the JSON, so an edit never wipes the stored key.
    apiKey = apiKey.takeIf { it.isNotBlank() },
    isDefault = makeDefault,
)

@Singleton
class DefaultLlmRepository @Inject constructor(
    private val api: AgentHubApi,
    private val dispatchers: DispatcherProvider,
) : LlmRepository {

    override suspend fun profiles(): AppResult<List<LlmProfile>> = withContext(dispatchers.io) {
        // Providers this app version does not know are skipped rather than shown half-broken.
        apiCall { api.llmProfiles() }.map { list -> list.mapNotNull { it.toDomain() } }
    }

    override suspend fun create(input: LlmProfileInput): AppResult<LlmProfile> = withContext(dispatchers.io) {
        mapOne(apiCall { api.createLlmProfile(input.toRequest()) })
    }

    override suspend fun update(id: String, input: LlmProfileInput): AppResult<LlmProfile> = withContext(dispatchers.io) {
        mapOne(apiCall { api.updateLlmProfile(id, input.toRequest()) })
    }

    override suspend fun delete(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        apiUnitCall { api.deleteLlmProfile(id) }
    }

    override suspend fun test(id: String): AppResult<TestResult> = withContext(dispatchers.io) {
        apiCall { api.testLlmProfile(id) }.map { TestResult(it.ok) }
    }

    override suspend fun testDraft(input: LlmProfileInput): AppResult<TestResult> = withContext(dispatchers.io) {
        apiCall { api.testLlmDraft(input.toRequest()) }.map { TestResult(it.ok) }
    }

    private fun mapOne(result: AppResult<LlmProfileDto>): AppResult<LlmProfile> = when (result) {
        is AppResult.Failure -> result
        is AppResult.Success -> result.value.toDomain()?.let { AppResult.Success(it) } ?: AppResult.Failure(DataError.Unknown)
    }
}
