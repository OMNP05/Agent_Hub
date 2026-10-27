package dev.agenthub.app.core.ui

import dev.agenthub.app.R
import dev.agenthub.app.core.error.DataError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ErrorMessagesTest {
    @Test
    fun knownErrorsHaveSpecificMessages() {
        assertEquals(R.string.error_llm_key_invalid, DataError.LlmKeyInvalid.messageRes())
        assertEquals(R.string.error_network, DataError.Network.messageRes())
        assertNotEquals(R.string.error_generic, DataError.ApprovalExpired.messageRes())
    }

    @Test
    fun missingGoogleAccountHasItsOwnMessage() {
        assertEquals(R.string.error_no_google_account, DataError.NoGoogleAccount.messageRes())
    }

    @Test
    fun unrecognisedBackendCodesNeverLeakToTheUser() {
        assertEquals(R.string.error_generic, DataError.Backend("secret_internal_code").messageRes())
        assertEquals(R.string.error_generic, DataError.Unknown.messageRes())
    }
}
