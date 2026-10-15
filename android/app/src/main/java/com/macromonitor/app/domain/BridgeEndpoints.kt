package com.macromonitor.app.domain

/**
 * Fallback address hints. No machine-specific IPs are baked in any more —
 * discovery (mDNS) and QR pairing provide the real address. This is only used to
 * pre-fill the field with a sensible default the user can edit, and to try the
 * loopback address on an emulator.
 */
object BridgeEndpoints {
    const val DEFAULT_PORT = 8787

    /** Port is the bridge default; host is intentionally generic. */
    const val LOCAL = "http://127.0.0.1:$DEFAULT_PORT"

    /** Placeholder shown when nothing has been discovered/paired yet. */
    const val PLACEHOLDER = LOCAL

    fun preferred(@Suppress("UNUSED_PARAMETER") onWifi: Boolean): String = LOCAL
}
