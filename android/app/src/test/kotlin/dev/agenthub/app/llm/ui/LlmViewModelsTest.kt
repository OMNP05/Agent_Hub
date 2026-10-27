package dev.agenthub.app.llm.ui

import androidx.lifecycle.SavedStateHandle
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.llm.FakeLlmRepository
import dev.agenthub.app.llm.data.FieldError
import dev.agenthub.app.llm.data.LlmProvider
import dev.agenthub.app.llm.data.TestResult
import dev.agenthub.app.llm.keyInvalid
import dev.agenthub.app.llm.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmViewModelsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun list(repo: FakeLlmRepository) = LlmProfilesViewModel(repo)
    private fun form(repo: FakeLlmRepository, id: String? = null) =
        LlmProfileFormViewModel(SavedStateHandle(if (id == null) emptyMap() else mapOf("profileId" to id)), repo)

    // ---- list ----

    @Test
    fun emptyListShowsTheEmptyState() {
        assertTrue(list(FakeLlmRepository()).uiState.value.showEmpty)
    }

    @Test
    fun theDefaultProfileComesFirstThenByName() {
        val repo = FakeLlmRepository(listOf(profile("b", "Zed"), profile("a", "Alpha"), profile("c", "Mid", isDefault = true)))
        assertEquals(listOf("c", "a", "b"), list(repo).uiState.value.profiles.map { it.id })
    }

    @Test
    fun loadFailureWithNothingShownIsABlockingError() {
        val repo = FakeLlmRepository().apply { listResult = AppResult.Failure(DataError.Network) }
        assertTrue(list(repo).uiState.value.showBlockingError)
    }

    @Test
    fun testingAProfileShowsPassedOrFailed() {
        val repo = FakeLlmRepository(listOf(profile("l1")))
        val v = list(repo)
        v.onTest(profile("l1"))
        assertEquals(TestState.Passed, v.uiState.value.testStates["l1"])
        assertEquals("l1", repo.testedId)

        repo.testResult = AppResult.Success(TestResult(false))
        v.onTest(profile("l1"))
        assertEquals(TestState.Failed, v.uiState.value.testStates["l1"])

        repo.testResult = keyInvalid
        v.onTest(profile("l1"))
        assertEquals(TestState.Failed, v.uiState.value.testStates["l1"])
    }

    @Test
    fun deleteNeedsConfirmationAndRemovesTheRowOnSuccess() {
        val repo = FakeLlmRepository(listOf(profile("l1"), profile("l2", "Other")))
        val v = list(repo)
        v.onDeleteRequest(profile("l1"))
        assertNull(repo.deletedId)
        v.onDeleteConfirm()
        assertEquals("l1", repo.deletedId)
        assertEquals(listOf("l2"), v.uiState.value.profiles.map { it.id })
    }

    @Test
    fun failedDeleteKeepsTheRowAndReportsTheError() {
        val repo = FakeLlmRepository(listOf(profile("l1"))).apply { deleteResult = AppResult.Failure(DataError.Network) }
        val v = list(repo)
        v.onDeleteRequest(profile("l1"))
        v.onDeleteConfirm()
        assertEquals(1, v.uiState.value.profiles.size)
        assertEquals(DataError.Network, v.uiState.value.actionError)
    }

    // ---- form ----

    private fun fillValid(v: LlmProfileFormViewModel) {
        v.onName("My Claude"); v.onModel("claude-x"); v.onApiKey("sk-live-abcdef123456")
    }

    @Test
    fun aNewFormStartsWithSafeDefaults() {
        val s = form(FakeLlmRepository()).uiState.value
        assertTrue(s.isNew)
        assertEquals(LlmProvider.OpenAi, s.provider)
        assertEquals("0.2", s.temperature)
        assertEquals("", s.apiKey)
    }

    @Test
    fun editingLoadsTheProfileButNeverTheKey() {
        val repo = FakeLlmRepository(listOf(profile("l1", keyLast4 = "9999")))
        val s = form(repo, "l1").uiState.value
        assertFalse(s.isNew)
        assertEquals("My Claude", s.name)
        assertEquals("", s.apiKey)
        assertEquals("9999", s.keyLast4)
        assertTrue(s.hasStoredKey)
    }

    @Test
    fun editingAMissingProfileIsReported() {
        assertTrue(form(FakeLlmRepository(), "gone").uiState.value.notFound)
    }

    @Test
    fun savingWithMissingFieldsShowsErrorsAndSendsNothing() {
        val repo = FakeLlmRepository()
        val v = form(repo)
        v.onSave()
        assertEquals(0, repo.saveCalls)
        assertEquals(FieldError.NameRequired, v.uiState.value.errors["name"])
        assertEquals(FieldError.ModelRequired, v.uiState.value.errors["model"])
        assertEquals(FieldError.KeyRequired, v.uiState.value.errors["apiKey"])
    }

    @Test
    fun anHttpCustomUrlIsRejectedBeforeAnyRequest() {
        val repo = FakeLlmRepository()
        val v = form(repo)
        fillValid(v)
        v.onProvider(LlmProvider.OpenAiCompatible)
        v.onBaseUrl("http://gateway.corp.example/v1")
        v.onSave()
        assertEquals(FieldError.BaseUrlNotHttps, v.uiState.value.errors["baseUrl"])
        assertEquals(0, repo.saveCalls)
    }

    @Test
    fun invalidNumbersAreReportedNotCrashed() {
        val v = form(FakeLlmRepository())
        fillValid(v)
        v.onTemperature("hot")
        v.onMaxTokens("lots")
        v.onSave()
        assertEquals(FieldError.TemperatureRange, v.uiState.value.errors["temperature"])
        assertEquals(FieldError.MaxTokensRange, v.uiState.value.errors["maxTokens"])
    }

    @Test
    fun savingSendsTheInputThenForgetsTheKey() = runTest {
        val repo = FakeLlmRepository()
        val v = form(repo)
        fillValid(v)
        v.onDefault(true)

        v.onSave()

        assertEquals("sk-live-abcdef123456", repo.created?.apiKey)
        assertEquals(true, repo.created?.makeDefault)
        assertEquals(dev.agenthub.app.llm.ui.LlmFormEffect.Saved, v.effects.first())
        assertEquals("the key must not stay in memory after saving", "", v.uiState.value.apiKey)
        assertEquals("3456", v.uiState.value.keyLast4)
    }

    @Test
    fun editingWithoutTypingAKeyKeepsTheStoredOne() = runTest {
        val repo = FakeLlmRepository(listOf(profile("l1")))
        val v = form(repo, "l1")
        v.onModel("claude-y")
        v.onSave()
        assertEquals("", repo.updated?.second?.apiKey)
        assertEquals("l1", repo.updated?.first)
    }

    @Test
    fun saveFailureKeepsTheFormAndShowsTheError() {
        val repo = FakeLlmRepository().apply { saveResult = keyInvalid }
        val v = form(repo)
        fillValid(v)
        v.onSave()
        assertEquals(DataError.LlmKeyInvalid, v.uiState.value.error)
        assertFalse(v.uiState.value.isSaving)
        assertNotNull(v.uiState.value.name)
    }

    @Test
    fun testingANewProfileUsesTheTypedKeyViaTheDraftEndpoint() {
        val repo = FakeLlmRepository()
        val v = form(repo)
        fillValid(v)
        v.onTest()
        assertEquals("sk-live-abcdef123456", repo.draftTested?.apiKey)
        assertEquals(TestState.Passed, v.uiState.value.testState)
        assertEquals(0, repo.saveCalls)
    }

    @Test
    fun testingASavedProfileWithNoNewKeyUsesTheStoredOne() {
        val repo = FakeLlmRepository(listOf(profile("l1")))
        val v = form(repo, "l1")
        v.onTest()
        assertEquals("l1", repo.testedId)
        assertNull(repo.draftTested)
    }

    @Test
    fun aFailedTestShowsFailedAndAnInvalidKeyMessage() {
        val repo = FakeLlmRepository().apply { testResult = keyInvalid }
        val v = form(repo)
        fillValid(v)
        v.onTest()
        assertEquals(TestState.Failed, v.uiState.value.testState)
        assertEquals(DataError.LlmKeyInvalid, v.uiState.value.error)
    }

    @Test
    fun anyEditResetsTheTestResult() {
        val v = form(FakeLlmRepository())
        fillValid(v)
        v.onTest()
        assertEquals(TestState.Passed, v.uiState.value.testState)
        v.onModel("another-model")
        assertEquals(TestState.Idle, v.uiState.value.testState)
    }

    @Test
    fun switchingToAnOfficialProviderClearsTheCustomUrl() {
        val v = form(FakeLlmRepository())
        v.onProvider(LlmProvider.OpenAiCompatible)
        v.onBaseUrl("https://gateway.corp.example")
        v.onProvider(LlmProvider.Anthropic)
        assertEquals("", v.uiState.value.baseUrl)
    }

    @Test
    fun theFormStateNeverPrintsTheKey() {
        val v = form(FakeLlmRepository())
        v.onApiKey("sk-live-abcdef123456")
        assertFalse(v.uiState.value.toString().contains("abcdef"))
    }
}
