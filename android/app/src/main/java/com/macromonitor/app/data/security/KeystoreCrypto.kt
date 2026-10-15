package com.macromonitor.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal AES-GCM helper backed by the Android Keystore.
 *
 * Used to encrypt the session token at rest so a raw dump of app preferences
 * does not yield a usable credential. The key never leaves secure hardware and
 * is never backed up, so after an uninstall / device restore decryption simply
 * fails and the caller falls back to requiring a fresh login.
 */
object KeystoreCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "macro_monitor_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_PREFIX_BYTES = 12

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Fetch the AES key, generating it on first use. */
    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt [plaintext] into `base64(iv || ciphertext)`. Returns null on failure. */
    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /** Decrypt a value produced by [encrypt]. Returns null on any failure. */
    fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_PREFIX_BYTES) {
                null
            } else {
                val iv = combined.copyOfRange(0, IV_PREFIX_BYTES)
                val ciphertext = combined.copyOfRange(IV_PREFIX_BYTES, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }
}
