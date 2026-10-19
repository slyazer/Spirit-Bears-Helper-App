package com.macromonitor.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Minimal AES-GCM helper backed by the Android Keystore.
 *
 * Used to encrypt the session token at rest so a raw dump of app preferences
 * does not yield a usable credential. The key never leaves secure hardware and
 * is never backed up, so after an uninstall / device restore decryption simply
 * fails and the caller falls back to requiring a fresh login.
 *
 * The envelope format and cipher handling live in [AesGcmEnvelope]; this object
 * supplies the Keystore-backed key and nothing else.
 */
object KeystoreCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "macro_monitor_session_key"

    private val envelope = AesGcmEnvelope { getOrCreateKey() }

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
    fun encrypt(plaintext: String): String? = envelope.encrypt(plaintext)

    /** Decrypt a value produced by [encrypt]. Returns null on any failure. */
    fun decrypt(encoded: String): String? = envelope.decrypt(encoded)
}
