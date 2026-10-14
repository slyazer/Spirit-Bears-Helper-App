package com.macromonitor.app.data.prefs

import android.content.Context

/**
 * Plaintext storage for the bridge URL and bearer token.
 *
 * Deliberately *not* encrypted (per request): normal SharedPreferences are
 * readable/writable without the Android Keystore, so the saved configuration
 * survives app updates and reinstalls instead of being wiped when a keystore
 * key is invalidated. Combined with `android:allowBackup="true"` this keeps the
 * bridge/token on the device across new versions.
 */
class SecureStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""

    fun tokenOrNull(): String? = prefs.getString(KEY_TOKEN, null)

    fun save(baseUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, normalize(baseUrl))
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && !tokenOrNull().isNullOrBlank()

    companion object {
        private const val PREFS_NAME = "macro_monitor_settings"
        private const val KEY_BASE_URL = "bridge_base_url"
        private const val KEY_TOKEN = "bridge_token"

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
