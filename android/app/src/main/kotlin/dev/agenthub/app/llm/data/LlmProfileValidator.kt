package dev.agenthub.app.llm.data

import java.net.URI

enum class FieldError { NameRequired, NameTooLong, BaseUrlRequired, BaseUrlNotHttps, BaseUrlInvalid, BaseUrlHasCredentials, ModelRequired, TemperatureRange, MaxTokensRange, KeyRequired }

/**
 * Early feedback only. The backend repeats every check and adds the real SSRF protection (resolving DNS,
 * blocking private, loopback and link-local ranges, no redirects); this is never the security boundary.
 */
object LlmProfileValidator {
    const val MAX_NAME = 60
    const val MIN_TEMPERATURE = 0.0
    const val MAX_TEMPERATURE = 2.0
    const val MIN_TOKENS = 1
    const val MAX_TOKENS = 200_000

    /** [isNew] profiles need a key; edits may leave it blank to keep the stored one. */
    fun validate(input: LlmProfileInput, isNew: Boolean): Map<String, FieldError> {
        val errors = linkedMapOf<String, FieldError>()
        val name = input.name.trim()
        if (name.isEmpty()) errors["name"] = FieldError.NameRequired
        else if (name.length > MAX_NAME) errors["name"] = FieldError.NameTooLong

        baseUrlError(input.baseUrl, input.provider)?.let { errors["baseUrl"] = it }

        if (input.model.isBlank()) errors["model"] = FieldError.ModelRequired
        if (input.temperature !in MIN_TEMPERATURE..MAX_TEMPERATURE || input.temperature.isNaN()) errors["temperature"] = FieldError.TemperatureRange
        if (input.maxTokens !in MIN_TOKENS..MAX_TOKENS) errors["maxTokens"] = FieldError.MaxTokensRange
        if (isNew && input.apiKey.isBlank()) errors["apiKey"] = FieldError.KeyRequired
        return errors
    }

    fun baseUrlError(raw: String, provider: LlmProvider): FieldError? {
        val url = raw.trim()
        if (url.isEmpty()) return if (provider.requiresBaseUrl) FieldError.BaseUrlRequired else null
        val uri = try { URI(url) } catch (e: Exception) { return FieldError.BaseUrlInvalid }
        if (uri.scheme == null || uri.host.isNullOrBlank()) return FieldError.BaseUrlInvalid
        if (!uri.scheme.equals("https", ignoreCase = true)) return FieldError.BaseUrlNotHttps
        // user:password@host would put a secret in a URL that gets stored and shown.
        if (uri.userInfo != null) return FieldError.BaseUrlHasCredentials
        return null
    }

    /** Lets the user type an empty temperature while editing without crashing; invalid text is rejected. */
    fun parseTemperature(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    fun parseMaxTokens(text: String): Int? = text.trim().toIntOrNull()

    /** The last four characters shown after saving; shorter keys are masked completely. */
    fun maskKey(last4: String?): String =
        if (last4.isNullOrBlank()) "" else "••••••••" + last4.takeLast(4)
}
