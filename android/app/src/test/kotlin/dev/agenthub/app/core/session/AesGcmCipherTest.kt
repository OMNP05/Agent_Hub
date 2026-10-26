package dev.agenthub.app.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsAndDoesNotStorePlaintext() {
        val cipher = AesGcmCipher { key }
        val text = "eyJhbGciOi.secret-token"
        val encrypted = cipher.encrypt(text)
        assertFalse(encrypted.contains("secret-token"))
        assertEquals(text, cipher.decrypt(encrypted))
    }

    @Test
    fun sameInputEncryptsDifferentlyEachTime() {
        val cipher = AesGcmCipher { key }
        assertNotEquals(cipher.encrypt("abc"), cipher.encrypt("abc"))
    }

    @Test(expected = GeneralSecurityException::class)
    fun tamperedCiphertextIsRejected() {
        val cipher = AesGcmCipher { key }
        val raw = Base64.getDecoder().decode(cipher.encrypt("abc"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        cipher.decrypt(Base64.getEncoder().encodeToString(raw))
    }

    @Test(expected = GeneralSecurityException::class)
    fun wrongKeyIsRejected() {
        val encrypted = AesGcmCipher { key }.encrypt("abc")
        AesGcmCipher { newKey() }.decrypt(encrypted)
    }

    @Test(expected = GeneralSecurityException::class)
    fun garbageInputIsRejected() {
        AesGcmCipher { key }.decrypt("not base64 !!!")
    }

    @Test
    fun sessionToStringHidesTokens() {
        assertFalse(Session("access-secret", "refresh-secret").toString().contains("secret"))
    }

    private val key: SecretKey = newKey()
}
