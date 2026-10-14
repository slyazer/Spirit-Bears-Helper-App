package com.macromonitor.app.data.repository

import com.macromonitor.app.data.api.AccountSummaryDto
import com.macromonitor.app.data.api.ApiClient
import com.macromonitor.app.data.api.CommandRequestDto
import com.macromonitor.app.data.api.CommandResponseDto
import com.macromonitor.app.data.api.LogEntryDto
import com.macromonitor.app.data.api.LogsResponseDto
import com.macromonitor.app.data.api.MacroApi
import com.macromonitor.app.data.api.PlantersResponseDto
import com.macromonitor.app.data.api.ReportImageDto
import com.macromonitor.app.data.api.ReportsResponseDto
import com.macromonitor.app.data.api.StatusDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for bridge data. Keeps Retrofit out of the UI layer and
 * maps transport errors into a small [BridgeResult] type.
 */
class MacroRepository(
    private val baseUrl: String,
    private val token: String,
) {
    private val api: MacroApi = ApiClient.create(baseUrl, token)

    val host: String = baseUrl

    suspend fun ping(): Result<String> = call { api.health().bridgeVersion }

    suspend fun accounts(): Result<List<AccountSummaryDto>> = call { api.accounts().accounts }

    suspend fun status(accountId: String): Result<StatusDto> = call { api.status(accountId) }

    suspend fun planters(accountId: String): Result<PlantersResponseDto> =
        call { api.planters(accountId) }

    suspend fun reports(accountId: String, limit: Int = 12): Result<ReportsResponseDto> =
        call { api.reports(accountId, limit) }

    suspend fun logs(accountId: String, limit: Int = 30): Result<LogsResponseDto> =
        call { api.logs(accountId, limit) }

    suspend fun sendCommand(accountId: String, command: String): Result<CommandResponseDto> =
        call { api.command(accountId, CommandRequestDto(command)) }

    fun imageUrl(proxyPath: String?): String? = ApiClient.absoluteImageUrl(baseUrl, proxyPath)

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
