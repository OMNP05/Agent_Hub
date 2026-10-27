package dev.agenthub.app.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProfileValidatorTest {
    private fun input(
        name: String = "My Claude",
        provider: LlmProvider = LlmProvider.Anthropic,
        baseUrl: String = "",
        model: String = "claude-sonnet",
        temperature: Double = 0.2,
        maxTokens: Int = 1000,
        apiKey: String = "sk-test-123456",
    ) = LlmProfileInput(name, provider, baseUrl, model, temperature, maxTokens, apiKey, makeDefault = false)

    @Test
    fun aCompleteProfileIsValid() {
        assertTrue(LlmProfileValidator.validate(input(), isNew = true).isEmpty())
    }

    @Test
    fun anyModelNameIsAccepted() {
        assertTrue(LlmProfileValidator.validate(input(model = "some-future-model-9000"), isNew = true).isEmpty())
        assertEquals(FieldError.ModelRequired, LlmProfileValidator.validate(input(model = "  "), isNew = true)["model"])
    }

    @Test
    fun officialProvidersMayLeaveTheUrlEmptyButCustomOnesMayNot() {
        assertNull(LlmProfileValidator.baseUrlError("", LlmProvider.OpenAi))
        assertNull(LlmProfileValidator.baseUrlError("", LlmProvider.Anthropic))
        assertEquals(FieldError.BaseUrlRequired, LlmProfileValidator.baseUrlError("", LlmProvider.OpenAiCompatible))
        assertEquals(FieldError.BaseUrlRequired, LlmProfileValidator.baseUrlError("   ", LlmProvider.OpenAiCompatible))
    }

    @Test
    fun onlyHttpsUrlsAreAccepted() {
        assertNull(LlmProfileValidator.baseUrlError("https://llm.corp.example/v1", LlmProvider.OpenAiCompatible))
        assertEquals(FieldError.BaseUrlNotHttps, LlmProfileValidator.baseUrlError("http://llm.corp.example/v1", LlmProvider.OpenAiCompatible))
        assertEquals(FieldError.BaseUrlNotHttps, LlmProfileValidator.baseUrlError("ftp://llm.corp.example", LlmProvider.OpenAiCompatible))
    }

    @Test
    fun malformedUrlsAreRejected() {
        assertEquals(FieldError.BaseUrlInvalid, LlmProfileValidator.baseUrlError("not a url", LlmProvider.OpenAiCompatible))
        assertEquals(FieldError.BaseUrlInvalid, LlmProfileValidator.baseUrlError("https://", LlmProvider.OpenAiCompatible))
        assertEquals(FieldError.BaseUrlInvalid, LlmProfileValidator.baseUrlError("llm.corp.example/v1", LlmProvider.OpenAiCompatible))
    }

    @Test
    fun urlsWithEmbeddedCredentialsAreRejected() {
        assertEquals(FieldError.BaseUrlHasCredentials, LlmProfileValidator.baseUrlError("https://user:pass@llm.corp.example", LlmProvider.OpenAiCompatible))
    }

    @Test
    fun aNewProfileNeedsAKeyButAnEditMayKeepTheStoredOne() {
        assertEquals(FieldError.KeyRequired, LlmProfileValidator.validate(input(apiKey = ""), isNew = true)["apiKey"])
        assertTrue(LlmProfileValidator.validate(input(apiKey = ""), isNew = false).isEmpty())
    }

    @Test
    fun numbersAreRangeChecked() {
        assertEquals(FieldError.TemperatureRange, LlmProfileValidator.validate(input(temperature = 2.5), true)["temperature"])
        assertEquals(FieldError.TemperatureRange, LlmProfileValidator.validate(input(temperature = -0.1), true)["temperature"])
        assertEquals(FieldError.TemperatureRange, LlmProfileValidator.validate(input(temperature = Double.NaN), true)["temperature"])
        assertEquals(FieldError.MaxTokensRange, LlmProfileValidator.validate(input(maxTokens = 0), true)["maxTokens"])
        assertEquals(FieldError.MaxTokensRange, LlmProfileValidator.validate(input(maxTokens = 1_000_000), true)["maxTokens"])
        assertTrue(LlmProfileValidator.validate(input(temperature = 0.0, maxTokens = 1), true).isEmpty())
        assertTrue(LlmProfileValidator.validate(input(temperature = 2.0, maxTokens = 200_000), true).isEmpty())
    }

    @Test
    fun nameIsRequiredAndLimited() {
        assertEquals(FieldError.NameRequired, LlmProfileValidator.validate(input(name = " "), true)["name"])
        assertEquals(FieldError.NameTooLong, LlmProfileValidator.validate(input(name = "x".repeat(61)), true)["name"])
    }

    @Test
    fun numberParsingToleratesCommasAndRejectsText() {
        assertEquals(0.7, LlmProfileValidator.parseTemperature("0,7")!!, 0.0)
        assertNull(LlmProfileValidator.parseTemperature("hot"))
        assertNull(LlmProfileValidator.parseTemperature(""))
        assertEquals(500, LlmProfileValidator.parseMaxTokens(" 500 "))
        assertNull(LlmProfileValidator.parseMaxTokens("5e3"))
    }

    @Test
    fun theKeyIsOnlyEverShownAsItsLastFourCharacters() {
        assertEquals("••••••••3456", LlmProfileValidator.maskKey("3456"))
        assertEquals("", LlmProfileValidator.maskKey(null))
        assertEquals("", LlmProfileValidator.maskKey(""))
        assertFalse(LlmProfileValidator.maskKey("abcdef3456").contains("abcdef"))
    }

    @Test
    fun theKeyNeverAppearsInTheTextFormOfAnInput() {
        val text = input(apiKey = "sk-super-secret-key").toString()
        assertFalse(text.contains("super-secret"))
    }
}
