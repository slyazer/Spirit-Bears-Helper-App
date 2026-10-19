package com.macromonitor.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator

/**
 * Exercises the AES-GCM envelope on the JVM with a plain JCE key. The
 * AndroidKeyStore glue in [KeystoreCrypto] is the only part that cannot be run
 * off-device; everything about the wire format is covered here.
 */
class AesGcmEnvelopeTest {

    private val key: javax.crypto.SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(256, SecureRandom()) }
        .generateKey()

    private val envelope = AesGcmEnvelope { key }

    @Test
    fun `round trip preserves the plaintext`() {
        val encoded = envelope.encrypt("session-token-abc123")

        assertEquals("session-token-abc123", encoded?.let { envelope.decrypt(it) })
    }

    @Test
    fun `round trip preserves unicode`() {
        val encoded = envelope.encrypt("пароль 🔐 token")

        assertEquals("пароль 🔐 token", encoded?.let { envelope.decrypt(it) })
    }

    @Test
    fun `an empty plaintext round trips`() {
        val encoded = envelope.encrypt("")

        assertEquals("", encoded?.let { envelope.decrypt(it) })
    }

    @Test
    fun `each encryption uses a fresh IV so identical secrets do not match`() {
        val first = envelope.encrypt("same-token")
        val second = envelope.encrypt("same-token")

        assertNotEquals("a repeated IV would leak that two tokens are equal", first, second)
        assertEquals("same-token", first?.let { envelope.decrypt(it) })
        assertEquals("same-token", second?.let { envelope.decrypt(it) })
    }

    @Test
    fun `the wire format is base64 of iv plus ciphertext with a 12 byte IV and no wrapping`() {
        val encoded = envelope.encrypt("token")

        assertNotNull(encoded)
        assertTrue("output must stay single-line (android.util.Base64.NO_WRAP)", !encoded!!.contains('\n'))
        val combined = Base64.getDecoder().decode(encoded)
        assertEquals(AesGcmEnvelope.IV_PREFIX_BYTES + "token".toByteArray().size + 16, combined.size)
    }

    @Test
    fun `a tampered ciphertext is rejected rather than returning garbage`() {
        val encoded = envelope.encrypt("important-token")!!
        val combined = Base64.getDecoder().decode(encoded)
        combined[combined.size - 1] = (combined[combined.size - 1].toInt() xor 0x01).toByte()

        assertNull(envelope.decrypt(Base64.getEncoder().encodeToString(combined)))
    }

    @Test
    fun `a tampered IV is rejected`() {
        val encoded = envelope.encrypt("important-token")!!
        val combined = Base64.getDecoder().decode(encoded)
        combined[0] = (combined[0].toInt() xor 0x01).toByte()

        assertNull(envelope.decrypt(Base64.getEncoder().encodeToString(combined)))
    }

    @Test
    fun `input too short to contain an IV is rejected`() {
        assertNull(envelope.decrypt(""))
        assertNull(envelope.decrypt(Base64.getEncoder().encodeToString(ByteArray(12))))
        assertNull(envelope.decrypt(Base64.getEncoder().encodeToString(ByteArray(4))))
    }

    @Test
    fun `unparseable or foreign input is rejected without throwing`() {
        assertNull(envelope.decrypt("not base64 !!!"))
        assertNull(envelope.decrypt(Base64.getEncoder().encodeToString("random bytes".toByteArray())))
    }

    @Test
    fun `a value encrypted under another key cannot be decrypted`() {
        val otherKey = KeyGenerator
            .getInstance("AES")
            .apply { init(256, SecureRandom()) }
            .generateKey()
        val foreign = AesGcmEnvelope { otherKey }.encrypt("token")!!

        assertNull(envelope.decrypt(foreign))
    }
}
