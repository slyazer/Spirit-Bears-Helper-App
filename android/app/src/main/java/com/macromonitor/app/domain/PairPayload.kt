package com.macromonitor.app.domain

import android.net.Uri

/**
 * Parses the pairing URI emitted by the bridge:
 *
 *     macromonitor://pair?h=<host>&p=<port>&n=<name>[&c=<code>]
 *
 * When a pairing code `c` is present the app redeems it for a session without
 * asking for a password; otherwise it just fills the address and asks the user
 * to sign in.
 */
data class PairPayload(
    val host: String,
    val port: Int,
    val name: String,
    val code: String?,
) {
    /** Base URL form, e.g. `http://192.168.1.100:8787`. */
    val baseUrl: String get() = "http://${host.trim()}:$port"

    companion object {
        const val SCHEME = "macromonitor"
        const val HOST = "pair"
        const val DEFAULT_PORT = 8787

        /** Return a [PairPayload] for a `macromonitor://pair?...` URI, else null. */
        fun parse(uriString: String?): PairPayload? {
            if (uriString.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(uriString.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(HOST, ignoreCase = true)) return null

            val host = uri.getQueryParameter("h")?.trim().orEmpty()
            if (host.isEmpty()) return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: DEFAULT_PORT
            val name = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() }
                ?: "Macro Monitor Bridge"
            val code = uri.getQueryParameter("c")?.trim()?.takeIf { it.isNotBlank() }
            return PairPayload(host = host, port = port, name = name, code = code)
        }
    }
}
