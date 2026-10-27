package dev.agenthub.app.llm

import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.llm.data.LlmProfile
import dev.agenthub.app.llm.data.LlmProfileInput
import dev.agenthub.app.llm.data.LlmProvider
import dev.agenthub.app.llm.data.LlmRepository
import dev.agenthub.app.llm.data.TestResult

fun profile(id: String = "l1", name: String = "My Claude", isDefault: Boolean = false, keyLast4: String? = "3456") =
    LlmProfile(id, name, LlmProvider.Anthropic, null, "claude-x", 0.2, 1000, keyLast4, isDefault)

class FakeLlmRepository(var stored: List<LlmProfile> = emptyList()) : LlmRepository {
    var listResult: AppResult<List<LlmProfile>>? = null
    var saveResult: AppResult<LlmProfile>? = null
    var testResult: AppResult<TestResult> = AppResult.Success(TestResult(true))
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)

    var created: LlmProfileInput? = null
    var updated: Pair<String, LlmProfileInput>? = null
    var draftTested: LlmProfileInput? = null
    var testedId: String? = null
    var deletedId: String? = null
    var saveCalls = 0

    override suspend fun profiles() = listResult ?: AppResult.Success(stored)

    override suspend fun create(input: LlmProfileInput): AppResult<LlmProfile> {
        saveCalls++; created = input
        return saveResult ?: AppResult.Success(profile(id = "new", name = input.name, keyLast4 = input.apiKey.takeLast(4)))
    }

    override suspend fun update(id: String, input: LlmProfileInput): AppResult<LlmProfile> {
        saveCalls++; updated = id to input
        return saveResult ?: AppResult.Success(profile(id = id, name = input.name))
    }

    override suspend fun delete(id: String): AppResult<Unit> { deletedId = id; return deleteResult }
    override suspend fun test(id: String): AppResult<TestResult> { testedId = id; return testResult }
    override suspend fun testDraft(input: LlmProfileInput): AppResult<TestResult> { draftTested = input; return testResult }
}

val keyInvalid = AppResult.Failure(DataError.LlmKeyInvalid)
