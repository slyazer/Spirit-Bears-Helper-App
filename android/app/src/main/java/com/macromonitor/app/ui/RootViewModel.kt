package com.macromonitor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromonitor.app.data.api.AccountSummaryDto
import com.macromonitor.app.data.api.LogEntryDto
import com.macromonitor.app.data.api.PlantersResponseDto
import com.macromonitor.app.data.api.ReportImageDto
import com.macromonitor.app.data.api.StatusDto
import com.macromonitor.app.data.repository.MacroRepository
import com.macromonitor.app.domain.BridgeEndpoints
import com.macromonitor.app.domain.ConnectionStatus
import com.macromonitor.app.domain.MacroCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { Connect, Dashboard, Reports, Logs }

data class UiState(
    val screen: Screen = Screen.Connect,
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val connectionMessage: String = "",
    val baseUrl: String = "",
    val accounts: List<AccountSummaryDto> = emptyList(),
    val selectedAccountId: String? = null,
    val status: StatusDto? = null,
    val planters: PlantersResponseDto? = null,
    val reports: List<ReportImageDto> = emptyList(),
    val reportsDetail: String? = null,
    val logs: List<LogEntryDto> = emptyList(),
    val logsDetail: String? = null,
    val selectedReport: ReportImageDto? = null,
    val commandHistory: List<Pair<String, String>> = emptyList(),
    val toast: String? = null,
    val loading: Boolean = false,
    val commandBusy: Boolean = false,
) {
    val selectedAccount: AccountSummaryDto?
        get() = accounts.firstOrNull { it.id == selectedAccountId }
}

class RootViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.macromonitor.app.MacroMonitorApplication).container
    private val secureStore = container.secureStore
    private val connectivity = container.connectivity

    private val _state = MutableStateFlow(
        UiState(
            baseUrl = if (secureStore.baseUrl.isNotBlank()) secureStore.baseUrl
            else BridgeEndpoints.preferred(connectivity.isOnWifi()),
            connection = if (secureStore.isConfigured()) ConnectionStatus.Checking
            else ConnectionStatus.Disconnected,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun repo(): MacroRepository? = container.repository()

    init {
        if (_state.value.connection == ConnectionStatus.Checking) {
            connect(secureStore.baseUrl, secureStore.tokenOrNull() ?: "")
        }
    }

    /** Validate + store credentials, ping the bridge, then switch to the app. */
    fun connect(inputBaseUrl: String, token: String) {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            _state.update {
                it.copy(connection = ConnectionStatus.Error, connectionMessage = "Enter the API token.")
            }
            return
        }
        val explicit = com.macromonitor.app.data.prefs.SecureStore.normalize(inputBaseUrl)
        val known = BridgeEndpoints.all
        val candidates = when {
            explicit.isBlank() -> {
                val pref = BridgeEndpoints.preferred(connectivity.isOnWifi())
                listOf(pref, BridgeEndpoints.all.first { it != pref })
            }
            explicit in known -> {
                val pref = explicit
                listOf(pref, known.first { it != pref })
            }
            else -> listOf(explicit)
        }.distinct()

        _state.update { it.copy(connection = ConnectionStatus.Checking, connectionMessage = "Checking bridge…") }
        viewModelScope.launch {
            try {
                if (!connectivity.canReachInternet()) {
                    _state.update {
                        it.copy(
                            connection = ConnectionStatus.Error,
                            connectionMessage = "No internet. Bridge is unreachable off the Tailscale network.",
                        )
                    }
                    return@launch
                }
                var lastError: Throwable? = null
                for (candidate in candidates) {
                    val repo = MacroRepository(candidate, trimmedToken)
                    val result = repo.ping()
                    if (result.isSuccess) {
                        secureStore.save(candidate, trimmedToken)
                        container.resetRepository()
                        _state.update {
                            it.copy(
                                baseUrl = candidate,
                                connection = ConnectionStatus.Connected,
                                connectionMessage = "Connected",
                                screen = Screen.Dashboard,
                            )
                        }
                        startPolling()
                        refreshAll(showToastOnError = false)
                        return@launch
                    } else {
                        lastError = result.exceptionOrNull()
                        if (candidates.size > 1) {
                            val other = candidates.first { it != candidate }
                            _state.update { it.copy(connectionMessage = "Trying alternate: $other…") }
                            delay(400)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        connection = ConnectionStatus.Error,
                        connectionMessage = "Bridge unreachable: ${lastError?.message ?: "unknown error"}",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        connection = ConnectionStatus.Error,
                        connectionMessage = e.message ?: "Connection failed",
                    )
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(10_000)
            }
        }
    }

    fun selectAccount(id: String) {
        _state.update { it.copy(selectedAccountId = id) }
        refreshAll(showToastOnError = true)
    }

    /**
     * Re-read the bridge's account list (picks up newly added / renamed accounts
     * from accounts.json) and refresh the selected account's data.
     */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            refreshAccounts()
            if (_state.value.selectedAccountId != null) {
                refreshStatus()
                refreshPlanters()
                refreshReportsNow()
                refreshLogsNow()
            }
            _state.update { it.copy(loading = false) }
            toast("Reloaded")
        }
    }

    fun refreshAll(showToastOnError: Boolean = true) {
        val id = _state.value.selectedAccountId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            refreshAccounts()
            refreshStatus()
            refreshPlanters()
            refreshReportsNow()
            refreshLogsNow()
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refreshAccounts() {
        repo()?.accounts()?.onSuccess { accounts ->
            val current = _state.value.selectedAccountId
            val stillExists = accounts.any { it.id == current }
            _state.update {
                it.copy(
                    accounts = accounts,
                    selectedAccountId = if (stillExists) current else accounts.firstOrNull()?.id,
                )
            }
        }
    }

    private suspend fun refreshStatus(showError: Boolean = false) {
        val id = _state.value.selectedAccountId
        if (id == null) {
            refreshAccounts()
            return
        }
        repo()?.status(id)?.onSuccess { st ->
            _state.update { it.copy(status = st) }
        }?.onFailure { e ->
            if (showError) toast("Status: ${e.message}")
        }
    }

    private suspend fun refreshPlanters() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.planters(id)?.onSuccess { p ->
            _state.update { it.copy(planters = p) }
        }
    }

    fun refreshReports() {
        viewModelScope.launch { refreshReportsNow() }
    }

    private suspend fun refreshReportsNow() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.reports(id)?.onSuccess { r ->
            _state.update { it.copy(reports = r.reports, reportsDetail = r.detail) }
        }
    }

    fun refreshLogs() {
        viewModelScope.launch { refreshLogsNow() }
    }

    private suspend fun refreshLogsNow() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.logs(id)?.onSuccess { l ->
            _state.update { it.copy(logs = l.logs, logsDetail = l.detail) }
        }
    }

    fun openReport(report: ReportImageDto) {
        _state.update { it.copy(screen = Screen.Reports, selectedReport = report) }
    }

    fun closeReport() {
        _state.update { it.copy(selectedReport = null) }
    }

    fun setScreen(screen: Screen) {
        if (screen == Screen.Dashboard) closeReport()
        _state.update { it.copy(screen = screen) }
        when (screen) {
            Screen.Reports -> viewModelScope.launch { refreshReports() }
            Screen.Logs -> viewModelScope.launch { refreshLogs() }
            else -> {}
        }
    }

    fun sendCommand(text: String) {
        val normalized = MacroCommands.normalize(text)
        if (normalized == null) {
            toast("Command not allowed. Use ${MacroCommands.ALLOWED.sorted()}")
            return
        }
        val id = _state.value.selectedAccountId ?: run { toast("No account selected"); return }
        _state.update { it.copy(commandBusy = true) }
        viewModelScope.launch {
            repo()?.sendCommand(id, normalized)?.onSuccess { resp ->
                val outcome = when (resp.result) {
                    "accepted" -> "Sent ✓"
                    else -> "${resp.result}: ${resp.detail ?: ""}"
                }
                _state.update { s ->
                    val newHistory = (listOf("?$normalized" to outcome) + s.commandHistory).take(8)
                    s.copy(commandHistory = newHistory)
                }
                toast(outcome)
            }?.onFailure { e ->
                toast("Command failed: ${e.message}")
            }
            _state.update { it.copy(commandBusy = false) }
        }
    }

    fun setHiveLevel(level: Int?) {
        val id = _state.value.selectedAccountId ?: return
        _state.update { s ->
            val updated = s.accounts.map {
                if (it.id == id) it.copy(hiveLevel = level) else it
            }
            s.copy(accounts = updated)
        }
    }

    fun dismissToast() {
        _state.update { it.copy(toast = null) }
    }

    private fun toast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun disconnect() {
        pollJob?.cancel()
        secureStore.clear()
        container.resetRepository()
        _state.value = UiState(
            baseUrl = "",
            connection = ConnectionStatus.Disconnected,
            connectionMessage = "",
        )
    }
}