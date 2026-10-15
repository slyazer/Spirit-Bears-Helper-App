package com.macromonitor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromonitor.app.data.api.AccountSummaryDto
import com.macromonitor.app.data.api.LogEntryDto
import com.macromonitor.app.data.api.PlantersResponseDto
import com.macromonitor.app.data.api.ReportImageDto
import com.macromonitor.app.data.api.StatusDto
import com.macromonitor.app.data.repository.BridgeHttpException
import com.macromonitor.app.data.repository.MacroRepository
import com.macromonitor.app.data.prefs.SecureStore
import com.macromonitor.app.domain.BridgeEndpoints
import com.macromonitor.app.domain.ConnectionStatus
import com.macromonitor.app.domain.MacroCommands
import com.macromonitor.app.domain.PairPayload
import com.macromonitor.app.net.DiscoveredBridge
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
    val username: String = "",
    val serverName: String = "",
    val discovered: List<DiscoveredBridge> = emptyList(),
    val discovering: Boolean = false,
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
            username = secureStore.username,
            connection = if (secureStore.isConfigured()) ConnectionStatus.Checking
            else ConnectionStatus.Disconnected,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var discoverJob: Job? = null

    fun repo(): MacroRepository? = container.repository()

    init {
        if (_state.value.connection == ConnectionStatus.Checking) {
            validateStoredSession()
        }
    }

    // -- session bootstrap ---------------------------------------------------

    /** Confirm a saved session still works, else drop to the sign-in screen. */
    private fun validateStoredSession() {
        viewModelScope.launch {
            val repository = container.repository()
            if (repository == null) {
                expireSession("Please sign in.")
                return@launch
            }
            val result = repository.me()
            result.onSuccess { me ->
                _state.update {
                    it.copy(
                        username = me.username,
                        connection = ConnectionStatus.Connected,
                        connectionMessage = "Connected",
                        screen = Screen.Dashboard,
                    )
                }
                startPolling()
                refreshAll(showToastOnError = false)
            }.onFailure { e ->
                if (e is BridgeHttpException && e.isUnauthorized) {
                    secureStore.clearSession()
                    container.resetRepository()
                    expireSession("Session expired — please sign in again.")
                } else {
                    _state.update {
                        it.copy(
                            connection = ConnectionStatus.Error,
                            connectionMessage = e.message ?: "Could not reach the bridge.",
                        )
                    }
                }
            }
        }
    }

    private fun expireSession(message: String) {
        pollJob?.cancel()
        container.resetRepository()
        _state.update {
            it.copy(
                connection = ConnectionStatus.Disconnected,
                connectionMessage = message,
                screen = Screen.Connect,
            )
        }
    }

    // -- login ---------------------------------------------------------------

    /** Sign in with a username + password against the configured address. */
    fun login(inputBaseUrl: String, username: String, password: String) {
        val url = resolveUrl(inputBaseUrl)
        val user = username.trim()
        if (url.isBlank()) {
            _state.update { it.copy(connection = ConnectionStatus.Error, connectionMessage = "Enter the bridge address.") }
            return
        }
        if (user.isBlank() || password.isBlank()) {
            _state.update { it.copy(connection = ConnectionStatus.Error, connectionMessage = "Enter your username and password.") }
            return
        }
        stopDiscovery()
        _state.update { it.copy(connection = ConnectionStatus.Checking, connectionMessage = "Signing in…", baseUrl = url) }
        viewModelScope.launch {
            val anonymous = container.anonymousRepository(url)
            anonymous.login(user, password, secureStore.deviceName)
                .onSuccess { token ->
                    establishSession(url, token.username.ifBlank { user }, token.token, token.expiresAt)
                }
                .onFailure { e -> _state.update { it.copy(connection = ConnectionStatus.Error, connectionMessage = friendly(e)) } }
        }
    }

    /** Redeem a scanned QR pairing payload: store the endpoint, then either pair or prompt login. */
    fun onPairPayload(payload: PairPayload) {
        val url = payload.baseUrl
        secureStore.saveEndpoint(url)
        stopDiscovery()
        _state.update {
            it.copy(baseUrl = url, serverName = payload.name, connectionMessage = "Found ${payload.name}")
        }
        val code = payload.code
        if (code == null) {
            _state.update {
                it.copy(
                    connection = ConnectionStatus.Disconnected,
                    connectionMessage = "Enter your username and password to finish setup.",
                )
            }
            return
        }
        _state.update { it.copy(connection = ConnectionStatus.Checking, connectionMessage = "Pairing…") }
        viewModelScope.launch {
            container.anonymousRepository(url).pair(code, secureStore.deviceName)
                .onSuccess { token ->
                    establishSession(url, token.username, token.token, token.expiresAt)
                }
                .onFailure { e -> _state.update { it.copy(connection = ConnectionStatus.Error, connectionMessage = friendly(e)) } }
        }
    }

    private fun establishSession(url: String, username: String, token: String, expiresAt: String?) {
        secureStore.save(url, username, token, expiresAt)
        container.resetRepository()
        _state.update {
            it.copy(
                baseUrl = url,
                username = username,
                connection = ConnectionStatus.Connected,
                connectionMessage = "Connected",
                screen = Screen.Dashboard,
            )
        }
        startPolling()
        refreshAll(showToastOnError = false)
    }

    private fun resolveUrl(input: String): String {
        val normalized = SecureStore.normalize(input)
        if (normalized.isNotBlank()) return normalized
        // Fall back to a discovered bridge or the built-in defaults.
        val discovered = _state.value.discovered.firstOrNull()?.baseUrl
        if (!discovered.isNullOrBlank()) return discovered
        return BridgeEndpoints.preferred(connectivity.isOnWifi())
    }

    // -- discovery -----------------------------------------------------------

    fun startDiscovery() {
        if (_state.value.discovering) return
        discoverJob?.cancel()
        _state.update { it.copy(discovering = true, discovered = emptyList(), connectionMessage = "Searching the network…") }
        discoverJob = viewModelScope.launch {
            try {
                container.discovery.discover().collect { bridge ->
                    _state.update { s ->
                        if (s.discovered.any { it.baseUrl == bridge.baseUrl }) s
                        else s.copy(discovered = s.discovered + bridge)
                    }
                }
            } finally {
                _state.update { it.copy(discovering = false) }
            }
        }
    }

    fun stopDiscovery() {
        discoverJob?.cancel()
        discoverJob = null
        _state.update { it.copy(discovering = false) }
    }

    fun useDiscovered(bridge: DiscoveredBridge) {
        stopDiscovery()
        secureStore.saveEndpoint(bridge.baseUrl)
        _state.update {
            it.copy(
                baseUrl = bridge.baseUrl,
                serverName = bridge.name,
                connection = ConnectionStatus.Disconnected,
                connectionMessage = "Enter your username and password.",
            )
        }
    }

    // -- data polling --------------------------------------------------------

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
        }?.onFailure { handleAuthFailure(it) }
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
            handleAuthFailure(e)
            if (showError) toast("Status: ${e.message}")
        }
    }

    private suspend fun refreshPlanters() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.planters(id)?.onSuccess { p ->
            _state.update { it.copy(planters = p) }
        }?.onFailure { handleAuthFailure(it) }
    }

    fun refreshReports() {
        viewModelScope.launch { refreshReportsNow() }
    }

    private suspend fun refreshReportsNow() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.reports(id)?.onSuccess { r ->
            _state.update { it.copy(reports = r.reports, reportsDetail = r.detail) }
        }?.onFailure { handleAuthFailure(it) }
    }

    fun refreshLogs() {
        viewModelScope.launch { refreshLogsNow() }
    }

    private suspend fun refreshLogsNow() {
        val id = _state.value.selectedAccountId ?: return
        repo()?.logs(id)?.onSuccess { l ->
            _state.update { it.copy(logs = l.logs, logsDetail = l.detail) }
        }?.onFailure { handleAuthFailure(it) }
    }

    /** Any 401 anywhere means the session is gone — bounce to sign-in. */
    private fun handleAuthFailure(e: Throwable) {
        if (e is BridgeHttpException && e.isUnauthorized) {
            secureStore.clearSession()
            container.resetRepository()
            expireSession("Session expired — please sign in again.")
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
                handleAuthFailure(e)
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

    /** Sign out: best-effort revoke on the bridge, then clear local state. */
    fun logout() {
        val repository = repo()
        viewModelScope.launch {
            runCatching { repository?.logout() }
        }
        fullReset()
    }

    /** Backwards-compatible alias used by the settings menu. */
    fun disconnect() = logout()

    private fun fullReset() {
        pollJob?.cancel()
        stopDiscovery()
        secureStore.clear()
        container.resetRepository()
        _state.value = UiState(
            baseUrl = BridgeEndpoints.preferred(connectivity.isOnWifi()),
            connection = ConnectionStatus.Disconnected,
            connectionMessage = "",
        )
    }

    private fun friendly(e: Throwable): String = e.message ?: "Something went wrong."
}
