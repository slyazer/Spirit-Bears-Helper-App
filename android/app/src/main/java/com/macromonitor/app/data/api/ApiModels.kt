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
