package com.macromonitor.app.data.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure AES-GCM envelope encoding a secret as `base64(iv || ciphertext)`.
 *
 * The key is supplied by the caller so this class has no Android dependency and
 * can be unit tested on the JVM; production callers pass an AndroidKeyStore key
 * (see [KeystoreCrypto]). Base64 uses the standard alphabet without line
 * wrapping, which is byte-for-byte what `android.util.Base64.NO_WRAP` produced
 * before this logic was split out - existing stored values stay readable.
 */
internal class AesGcmEnvelope(private val key: () -> SecretKey) {

    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(iv + ciphertext)
    } catch (e: Exception) {
        null
    }

    fun decrypt(encoded: String): String? = try {
        val combined = Base64.getDecoder().decode(encoded)
        if (combined.size <= IV_PREFIX_BYTES) {
            null
        } else {
            val iv = combined.copyOfRange(0, IV_PREFIX_BYTES)
            val ciphertext = combined.copyOfRange(IV_PREFIX_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_PREFIX_BYTES = 12
    }
}
