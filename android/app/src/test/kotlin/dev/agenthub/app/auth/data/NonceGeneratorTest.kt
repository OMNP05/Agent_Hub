package dev.agenthub.app.auth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonceGeneratorTest {

    @Test
    fun `google receives the sha-256 hex of the raw nonce`() {
        // Known vector: SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NonceGenerator.forGoogle("abc"),
        )
    }

    @Test
    fun `hashed nonce is 64 lowercase hex characters and differs from the raw one`() {
        val raw = NonceGenerator.generate()
        val hashed = NonceGenerator.forGoogle(raw)
        assertEquals(64, hashed.length)
        assertTrue(hashed.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(raw, hashed)
    }

    @Test
    fun `raw nonces are unique and long enough`() {
        val nonces = List(50) { NonceGenerator.generate() }
        assertEquals(50, nonces.toSet().size)
        assertTrue(nonces.all { it.length >= 43 })
    }
}
