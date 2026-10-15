package com.macromonitor.app.data.prefs

import android.content.Context
import com.macromonitor.app.data.security.KeystoreCrypto

/**
 * Persistent connection config for the app.
 *
 * The non-secret parts (bridge URL, username, device name) are stored as plain
 * preferences so they survive updates *and* reinstalls — a returning user just
 * re-enters their password. The **session token** is encrypted with AES-GCM via
 * the Android Keystore ([KeystoreCrypto]) so it is not readable from a raw
 * preferences dump. Passwords are never stored.
 *
 * If the Keystore key is gone (uninstall / device restore), decryption fails and
 * the token is treated as missing, forcing a fresh login.
 */
class SecureStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""

    val username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""

    val deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "")?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()

    val expiresAt: String?
        get() = prefs.getString(KEY_EXPIRES_AT, null)

    /** Decrypt the stored session token, or null when absent/unreadable. */
    fun sessionTokenOrNull(): String? {
        val encrypted = prefs.getString(KEY_TOKEN_ENC, null) ?: return null
        return KeystoreCrypto.decrypt(encrypted)
    }

    fun save(
        baseUrl: String,
        username: String,
        token: String,
        expiresAt: String?,
        deviceName: String? = null,
    ) {
        val encrypted = KeystoreCrypto.encrypt(token)
        prefs.edit()
            .putString(KEY_BASE_URL, normalize(baseUrl))
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_TOKEN_ENC, encrypted)
            .putString(KEY_EXPIRES_AT, expiresAt)
            .apply {
                if (!deviceName.isNullOrBlank()) putString(KEY_DEVICE_NAME, deviceName.trim())
            }
            .apply()
    }

    /** Remember the address of a discovered/paired bridge before login. */
    fun saveEndpoint(baseUrl: String) {
        prefs.edit().putString(KEY_BASE_URL, normalize(baseUrl)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN_ENC)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && username.isNotBlank() && !sessionTokenOrNull().isNullOrBlank()

    private fun defaultDeviceName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    companion object {
        private const val PREFS_NAME = "macro_monitor_settings"
        private const val KEY_BASE_URL = "bridge_base_url"
        private const val KEY_USERNAME = "bridge_username"
        private const val KEY_DEVICE_NAME = "bridge_device_name"
        private const val KEY_TOKEN_ENC = "bridge_token_enc"
        private const val KEY_EXPIRES_AT = "bridge_token_expires"

        /** Accept "host:port", "http://host:port", or a full URL. */
        fun normalize(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}
