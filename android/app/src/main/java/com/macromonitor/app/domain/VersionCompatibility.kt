package com.macromonitor.app.domain

/**
 * Client side of the Macro Monitor version compatibility contract.
 *
 * The bridge advertises `api_version` in the public `GET /api/v1/info` response
 * (see `bridge/app/models.py`). The app declares the single API version it was
 * built against here. The two must match for the app to consider the bridge
 * compatible.
 *
 * Policy (deliberately simple and deterministic):
 *  - equal `api_version`            -> [Result.Compatible]
 *  - a different `api_version`      -> [Result.Incompatible] (update both sides)
 *  - missing / blank `api_version`  -> [Result.Unknown] (likely an older bridge;
 *    warn but do not block, so the user keeps access to diagnostics)
 *
 * `bridge_version` is informational only - it is shown in messages but never
 * decides compatibility, because a bridge can bump its version without breaking
 * the API.
 */
object VersionCompatibility {

    /** The bridge API version this build of the app speaks. */
    const val SUPPORTED_API_VERSION: String = "v1"

    enum class Result { Compatible, Incompatible, Unknown }

    fun evaluate(bridgeApiVersion: String?): Result = when {
        bridgeApiVersion.isNullOrBlank() -> Result.Unknown
        bridgeApiVersion.trim() == SUPPORTED_API_VERSION -> Result.Compatible
        else -> Result.Incompatible
    }

    /**
     * Human readable explanation, or null when there is nothing to warn about.
     * Never returns null for [Result.Incompatible] - a mismatch must always be
     * explained.
     */
    fun message(
        result: Result,
        bridgeApiVersion: String?,
        bridgeVersion: String?,
    ): String? {
        val bridge = bridgeVersion?.takeIf { it.isNotBlank() } ?: "unknown version"
        return when (result) {
            Result.Compatible -> null
            Result.Incompatible ->
                "Bridge version mismatch: this app speaks API " +
                    "$SUPPORTED_API_VERSION but the PC bridge ($bridge) speaks API " +
                    "${bridgeApiVersion?.trim()}. Install matching versions of the " +
                    "Android app and the PC bridge."
            Result.Unknown ->
                "This bridge ($bridge) did not report an API version, so it may be " +
                    "an older PC bridge. If status or commands fail, update the PC " +
                    "bridge to a version matching this app."
        }
    }
}
