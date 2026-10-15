package com.macromonitor.app.data.repository

import com.macromonitor.app.data.api.AccountSummaryDto
import com.macromonitor.app.data.api.ApiClient
import com.macromonitor.app.data.api.ChangePasswordRequestDto
import com.macromonitor.app.data.api.CommandRequestDto
import com.macromonitor.app.data.api.CommandResponseDto
import com.macromonitor.app.data.api.InfoDto
import com.macromonitor.app.data.api.LogEntryDto
import com.macromonitor.app.data.api.LoginRequestDto
import com.macromonitor.app.data.api.LogsResponseDto
import com.macromonitor.app.data.api.MacroApi
import com.macromonitor.app.data.api.MeDto
import com.macromonitor.app.data.api.PairRequestDto
import com.macromonitor.app.data.api.PlantersResponseDto
import com.macromonitor.app.data.api.ReportImageDto
import com.macromonitor.app.data.api.ReportsResponseDto
import com.macromonitor.app.data.api.StatusDto
import com.macromonitor.app.data.api.TokenDto
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/** A non-2xx response from the bridge, carrying the HTTP status. */
class BridgeHttpException(val code: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = code == 401
    val isRateLimited: Boolean get() = code == 429
}

/** A transport-level failure (timeout, refused connection, DNS, …). */
class BridgeNetworkException(message: String) : Exception(message)

/**
 * Single entry point for bridge data. Keeps Retrofit out of the UI layer and
 * maps transport errors into a small, typed result.
 */
class MacroRepository(
    private val baseUrl: String,
    private val token: String?,
) {
    private val api: MacroApi = ApiClient.create(baseUrl, token)

    val host: String = baseUrl

    // -- public / discovery --------------------------------------------------
    suspend fun ping(): Result<String> = call { api.health().bridgeVersion }
    suspend fun info(): Result<InfoDto> = call { api.info() }

    // -- auth ----------------------------------------------------------------
    suspend fun login(username: String, password: String, deviceName: String?): Result<TokenDto> =
        call { api.login(LoginRequestDto(username.trim(), password, deviceName)) }

    suspend fun pair(code: String, deviceName: String?): Result<TokenDto> =
        call { api.pair(PairRequestDto(code.trim().uppercase(), deviceName)) }

    suspend fun me(): Result<MeDto> = call { api.me() }

    suspend fun logout(): Result<Unit> = call { api.logout(); Unit }

    suspend fun changePassword(current: String, new: String): Result<Unit> =
        call { api.changePassword(ChangePasswordRequestDto(current, new)); Unit }

    // -- data ----------------------------------------------------------------
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
        } catch (e: HttpException) {
            Result.failure(e.toBridgeError())
        } catch (e: IOException) {
            Result.failure(BridgeNetworkException(e.message ?: "Network error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun HttpException.toBridgeError(): BridgeHttpException {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val detail = body?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject.get("detail")?.asString }
                .getOrNull()
        }
        val friendly = detail ?: when (code()) {
            401 -> "Incorrect username or password."
            403 -> "Not allowed."
            404 -> "Not found."
            422 -> "That value was rejected."
            429 -> "Too many attempts. Please wait and try again."
            else -> "Bridge error (HTTP ${code()})."
        }
        return BridgeHttpException(code(), friendly)
    }
}
