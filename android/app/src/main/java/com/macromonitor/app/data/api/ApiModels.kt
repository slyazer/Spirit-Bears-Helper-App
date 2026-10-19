package com.macromonitor.app.data.api

import com.google.gson.annotations.SerializedName

data class HealthDto(
    val status: String = "ok",
    @SerializedName("bridge_version") val bridgeVersion: String = "",
    @SerializedName("server_time") val serverTime: String? = null,
)

data class AccountSummaryDto(
    val id: String,
    val name: String,
    @SerializedName("hive_level") val hiveLevel: Int? = null,
    @SerializedName("command_ambiguous") val commandAmbiguous: Boolean = false,
)

data class AccountsResponseDto(val accounts: List<AccountSummaryDto> = emptyList())

data class StatusDto(
    val id: String,
    val name: String,
    @SerializedName("hive_level") val hiveLevel: Int? = null,
    @SerializedName("macro_state") val macroState: String = "unknown",
    val field: String? = null,
    @SerializedName("field_boosted") val fieldBoosted: Boolean? = null,
    @SerializedName("field_since") val fieldSince: String? = null,
    @SerializedName("field_limit_seconds") val fieldLimitSeconds: Int? = null,
    val activity: String? = null,
    @SerializedName("activity_detail") val activityDetail: String? = null,
    @SerializedName("honey_total") val honeyTotal: String? = null,
    @SerializedName("honey_per_hour") val honeyPerHour: String? = null,
    @SerializedName("runtime_seconds") val runtimeSeconds: Long? = null,
    @SerializedName("disconnected_count") val disconnectedCount: Int? = null,
    @SerializedName("planters_collected") val plantersCollected: Int? = null,
    @SerializedName("last_updated") val lastUpdated: String? = null,
    val stale: Boolean = false,
    val source: String = "",
)

/**
 * Latest known population of the owner's Roblox private server, from
 * `GET /api/v1/roblox/population`. Only the two integers are ever present; when
 * no capture has succeeded yet [available] is false and the counts are null.
 * [stale] marks a last-known value whose capture is older than the freshness
 * window, so the UI can dim it instead of presenting it as current.
 */
data class PopulationDto(
    val available: Boolean = false,
    val playing: Int? = null,
    @SerializedName("max_players") val maxPlayers: Int? = null,
    @SerializedName("captured_at") val capturedAt: String? = null,
    @SerializedName("received_at") val receivedAt: String? = null,
    @SerializedName("age_seconds") val ageSeconds: Int? = null,
    val stale: Boolean = false,
    val source: String = "roblox-web-screenshot",
    val detail: String? = null,
)

data class ReportImageDto(
    @SerializedName("report_id") val reportId: String,
    @SerializedName("arrival_time") val arrivalTime: String,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("image_proxy_url") val imageProxyUrl: String? = null,
    val author: String? = null,
    val summary: String? = null,
)

data class ReportsResponseDto(
    val id: String = "",
    val reports: List<ReportImageDto> = emptyList(),
    val detail: String? = null,
)

data class LogEntryDto(
    @SerializedName("entry_id") val entryId: String,
    val timestamp: String? = null,
    val title: String = "",
    val body: String? = null,
    val color: String? = null,
    val author: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
)

data class LogsResponseDto(
    val id: String = "",
    val logs: List<LogEntryDto> = emptyList(),
    val detail: String? = null,
)

data class PlanterDto(
    val slot: Int,
    val name: String? = null,
    val field: String? = null,
    @SerializedName("harvest_eta") val harvestEta: String? = null,
    @SerializedName("est_percent") val estPercent: Double? = null,
)

data class NectarDto(
    val name: String = "",
    val level: Double? = null,
)

data class PlantersResponseDto(
    val id: String = "",
    val available: Boolean = false,
    val reason: String? = null,
    val planters: List<PlanterDto> = emptyList(),
    val nectars: List<NectarDto> = emptyList(),
)

data class CommandRequestDto(
    val command: String,
    @SerializedName("correlation_id") val correlationId: String? = null,
)

data class CommandResponseDto(
    val id: String = "",
    val command: String = "",
    val result: String = "",
    @SerializedName("correlation_id") val correlationId: String? = null,
    val detail: String? = null,
)

// ---------------------------------------------------------------------------
// Auth & discovery
// ---------------------------------------------------------------------------

/** Public service descriptor from `GET /api/v1/info` (no secrets). */
data class InfoDto(
    val name: String = "Macro Monitor Bridge",
    @SerializedName("bridge_version") val bridgeVersion: String = "",
    @SerializedName("pairing_enabled") val pairingEnabled: Boolean = true,
    val accounts: Int = 0,
    /** Nullable: absent on bridges that predate the version handshake. */
    val scheme: String? = null,
    @SerializedName("api_version") val apiVersion: String? = null,
)

data class LoginRequestDto(
    val username: String,
    val password: String,
    @SerializedName("device_name") val deviceName: String? = null,
)

data class PairRequestDto(
    val code: String,
    @SerializedName("device_name") val deviceName: String? = null,
)

data class ChangePasswordRequestDto(
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String,
)

data class TokenDto(
    val token: String = "",
    @SerializedName("token_type") val tokenType: String = "Bearer",
    val username: String = "",
    @SerializedName("expires_at") val expiresAt: String? = null,
)

data class MeDto(
    val username: String = "",
    val role: String = "owner",
    val device: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
)

/** Generic `{ "status": "ok" }` acknowledgement. */
data class OkDto(val status: String = "ok")
