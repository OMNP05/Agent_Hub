package dev.agenthub.app.core.network

import dev.agenthub.app.core.error.DataError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorParserTest {
    @Test
    fun readsErrorCode() {
        assertEquals("llm_key_invalid", ApiErrorParser.code("""{"error":{"code":"llm_key_invalid"}}"""))
    }

    @Test
    fun malformedOrMissingBodiesYieldNoCode() {
        assertNull(ApiErrorParser.code(null))
        assertNull(ApiErrorParser.code("<html>502</html>"))
        assertNull(ApiErrorParser.code("""{"detail":"x"}"""))
    }

    @Test
    fun fallsBackToStatusWhenCodeUnknown() {
        assertEquals(DataError.Unauthorized, ApiErrorParser.toError(401, "oops"))
        assertEquals(DataError.Unknown, ApiErrorParser.toError(500, null))
        assertEquals(DataError.LlmKeyInvalid, ApiErrorParser.toError(402, """{"error":{"code":"llm_key_invalid"}}"""))
    }
}
