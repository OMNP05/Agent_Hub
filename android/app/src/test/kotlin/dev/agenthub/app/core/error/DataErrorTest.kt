package dev.agenthub.app.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataErrorTest {
    @Test
    fun knownBackendCodesMapToDomainErrors() {
        assertEquals(DataError.LlmKeyInvalid, DataError.fromCode("llm_key_invalid"))
        assertEquals(DataError.ConnectionNeedsReauth, DataError.fromCode("connection_needs_reauth"))
        assertEquals(DataError.ApprovalExpired, DataError.fromCode("approval_expired"))
        assertEquals(DataError.AgentBusy, DataError.fromCode("agent_busy"))
    }

    @Test
    fun missingCodeIsUnknownAndUnrecognisedCodeIsKept() {
        assertEquals(DataError.Unknown, DataError.fromCode(null))
        assertEquals(DataError.Unknown, DataError.fromCode(""))
        assertTrue(DataError.fromCode("something_new") is DataError.Backend)
    }

    @Test
    fun mapTransformsSuccessAndPassesFailureThrough() {
        val ok: AppResult<Int> = AppResult.Success(2)
        assertEquals(AppResult.Success(4), ok.map { it * 2 })
        val bad: AppResult<Int> = AppResult.Failure(DataError.Network)
        assertEquals(bad, bad.map { it * 2 })
    }
}
