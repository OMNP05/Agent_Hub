package dev.agenthub.app.llm.data

enum class LlmProvider(val wire: String, val label: String, val defaultBaseUrl: String?) {
    OpenAi("openai", "OpenAI", "https://api.openai.com/v1"),
    Anthropic("anthropic", "Anthropic", "https://api.anthropic.com"),

    /** Any OpenAI-compatible endpoint, such as a company gateway that resells Claude or GPT. */
    OpenAiCompatible("openai_compatible", "OpenAI-compatible", null);

    /** Official providers may leave the base URL empty; a custom endpoint must always have one. */
    val requiresBaseUrl: Boolean get() = defaultBaseUrl == null

    companion object {
        fun fromWire(value: String?): LlmProvider? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A profile as the app knows it. There is deliberately no field for the key: the backend returns only
 * the last four characters, and the key itself can be sent but never read back (blueprint 8.3).
 */
data class LlmProfile(
    val id: String,
    val name: String,
    val provider: LlmProvider,
    val baseUrl: String?,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val keyLast4: String?,
    val isDefault: Boolean,
)

/** What the user typed. [apiKey] is blank on edit when the existing key should be kept. */
data class LlmProfileInput(
    val name: String,
    val provider: LlmProvider,
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val apiKey: String,
    val makeDefault: Boolean,
) {
    // The key must never end up in a log line or an error message through a generated toString.
    override fun toString(): String = "LlmProfileInput(name=$name, provider=$provider, model=$model, apiKey=***)"
}

data class TestResult(val ok: Boolean)
