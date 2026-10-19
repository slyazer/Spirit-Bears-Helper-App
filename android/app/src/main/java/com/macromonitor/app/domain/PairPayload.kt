package com.macromonitor.app.domain

import java.io.ByteArrayOutputStream

/**
 * Parses the pairing URI emitted by the bridge:
 *
 *     macromonitor://pair?h=<host>&p=<port>&n=<name>[&c=<code>]
 *
 * When a pairing code `c` is present the app redeems it for a session without
 * asking for a password; otherwise it just fills the address and asks the user
 * to sign in.
 *
 * The parser is pure Kotlin on purpose: `android.net.Uri` cannot be exercised by
 * JVM unit tests, and the URI grammar used here is fixed and tiny. Decoding
 * matches the Android `Uri` behaviour the QR flow relied on (percent escapes,
 * `+` for a space, first value wins), and mirrors `build_payload()` /
 * `parse_payload()` in `bridge/app/pairing.py`.
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
            val uri = uriString.trim()

            val schemeEnd = uri.indexOf("://")
            if (schemeEnd < 0) return null
            // The scheme must be EXACTLY "macromonitor". A prefix-only check would
            // accept "macromonitorX://" or "macromonitorEVIL://"; requiring the
            // scheme to end where the authority begins rejects those.
            if (schemeEnd != SCHEME.length) return null
            if (!uri.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
            if (uri.length <= schemeEnd + 3) return null

            // Everything after "://", minus any fragment (never part of the query).
            val afterScheme = uri.substring(schemeEnd + 3).substringBefore('#')
            val queryStart = afterScheme.indexOf('?')
            val authority = if (queryStart >= 0) afterScheme.substring(0, queryStart) else afterScheme
            val query = if (queryStart >= 0) afterScheme.substring(queryStart + 1) else null

            // Only the host matters; an optional ":port" in the authority is ignored,
            // matching how android.net.Uri exposes `host`.
            val authorityHost = authority.substringAfter('@').substringBefore(':')
            if (!authorityHost.equals(HOST, ignoreCase = true)) return null

            val params = parseQuery(query)

            val host = params["h"]?.trim().orEmpty()
            if (host.isEmpty()) return null
            val port = params["p"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
            val name = params["n"]?.trim()?.takeIf { it.isNotBlank() } ?: "Macro Monitor Bridge"
            val code = params["c"]?.trim()?.takeIf { it.isNotBlank() }
            return PairPayload(host = host, port = port, name = name, code = code)
        }

        /** `a=1&b=x%20y&b=z` -> first value per key, percent- and `+`-decoded. */
        private fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val key = decode(if (separator >= 0) pair.substring(0, separator) else pair)
                if (key.isEmpty() || key in out) continue
                val value = if (separator >= 0) pair.substring(separator + 1) else ""
                out[key] = decode(value)
            }
            return out
        }

        /**
         * Percent-decode with UTF-8, treating a lone `+` as a space. Mirrors
         * `android.net.Uri.decode`, which the QR flow used before this parser.
         */
        private fun decode(value: String): String {
            if (value.none { it == '%' || it == '+' }) return value
            val out = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '+' -> {
                        out.write(' '.code)
                        i++
                    }
                    c == '%' && i + 2 < value.length -> {
                        val hi = Character.digit(value[i + 1], 16)
                        val lo = Character.digit(value[i + 2], 16)
                        if (hi < 0 || lo < 0) {
                            // Not a valid escape: keep the '%' literally, like Uri does.
                            out.write('%'.code)
                            i++
                        } else {
                            out.write((hi shl 4) or lo)
                            i += 3
                        }
                    }
                    else -> {
                        val bytes = c.toString().toByteArray(Charsets.UTF_8)
                        out.write(bytes, 0, bytes.size)
                        i++
                    }
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
