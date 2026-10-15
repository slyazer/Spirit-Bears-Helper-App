package com.macromonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macromonitor.app.domain.ConnectionStatus
import com.macromonitor.app.ui.components.AccountSwitcher
import com.macromonitor.app.ui.components.ConnectionIndicator
import com.macromonitor.app.ui.screens.ConnectScreen
import com.macromonitor.app.ui.screens.DashboardScreen
import com.macromonitor.app.ui.screens.LogsScreen
import com.macromonitor.app.ui.screens.ReportsScreen
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.BrandEnd
import com.macromonitor.app.ui.theme.BrandMid
import com.macromonitor.app.ui.theme.BrandStart
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Panel
import com.macromonitor.app.ui.theme.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

@Composable
fun MacroMonitorRoot(
    vm: RootViewModel = viewModel(),
    pairUri: String? = null,
    onPairConsumed: () -> Unit = {},
) {
    val s by vm.state.collectAsState()
    var showScanner by remember { mutableStateOf(false) }

    // A `macromonitor://pair?...` deep link (or QR scan) feeds the same handler.
    LaunchedEffect(pairUri) {
        val payload = com.macromonitor.app.domain.PairPayload.parse(pairUri)
        if (payload != null) {
            showScanner = false
            vm.onPairPayload(payload)
        }
        if (pairUri != null) onPairConsumed()
    }

    com.macromonitor.app.ui.theme.MacroMonitorTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            if (showScanner) {
                com.macromonitor.app.ui.screens.QrScanScreen(
                    onScanned = { payload ->
                        showScanner = false
                        vm.onPairPayload(payload)
                    },
                    onClose = { showScanner = false },
                )
            } else if (s.connection == ConnectionStatus.Connected) {
                MainScaffold(vm, s)
            } else {
                ConnectScreen(
                    connection = s.connection,
                    message = s.connectionMessage,
                    initialBaseUrl = s.baseUrl,
                    initialUsername = s.username,
                    discovered = s.discovered,
                    discovering = s.discovering,
                    onLogin = vm::login,
                    onScanQr = { showScanner = true },
                    onStartDiscovery = vm::startDiscovery,
                    onStopDiscovery = vm::stopDiscovery,
                    onUseDiscovered = vm::useDiscovered,
                )
            }

            val toast = s.toast
            if (toast != null) {
                LaunchedEffect(toast) {
                    delay(2600)
                    vm.dismissToast()
                }
                ToastBanner(toast, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun ToastBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(message, color = Text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MainScaffold(vm: RootViewModel, s: UiState) {
    var overflowMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            BottomNav(current = s.screen, onSelect = vm::setScreen)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TopBar(
                accountName = s.selectedAccount?.name ?: "No account",
                loading = s.loading,
                onReload = vm::reload,
                onSettings = { overflowMenu = true },
            )
            AccountSwitcher(
                accounts = s.accounts,
                selected = s.selectedAccountId,
                onSelect = vm::selectAccount,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (s.screen) {
                    Screen.Connect, Screen.Dashboard -> DashboardScreen(
                        status = s.status,
                        planters = s.planters,
                        commandHistory = s.commandHistory,
                        commandBusy = s.commandBusy,
                        onCommand = vm::sendCommand,
                    )
                    Screen.Reports -> ReportsScreen(
                        reports = s.reports,
                        detail = s.reportsDetail,
                        selected = s.selectedReport,
                        imageUrl = { path ->
                            if (path.isNullOrBlank()) null else vm.repo()?.imageUrl(path)
                        },
                        onOpen = vm::openReport,
                        onClose = vm::closeReport,
                        onRefresh = { vm.refreshReports() },
                    )
                    Screen.Logs -> LogsScreen(
                        logs = s.logs,
                        detail = s.logsDetail,
                        imageUrl = { path ->
                            if (path.isNullOrBlank()) null else vm.repo()?.imageUrl(path)
                        },
                        onRefresh = { vm.refreshLogs() },
                    )
                }
            }
        }
    }

    DropdownMenu(
        expanded = overflowMenu,
        onDismissRequest = { overflowMenu = false },
    ) {
        DropdownMenuItem(
            text = { Text("Disconnect") },
            onClick = {
                overflowMenu = false
                vm.disconnect()
            },
            leadingIcon = { Icon(Icons.Filled.Logout, contentDescription = null) },
        )
    }
}

@Composable
private fun TopBar(accountName: String, loading: Boolean, onReload: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(BrandStart, BrandMid, BrandEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(com.macromonitor.app.R.drawable.ic_spirit_bear),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                "Spirit Bear's Helper App",
                color = Text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                accountName,
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onReload, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Accent,
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = Muted)
            }
        }
        ConnectionIndicator(connected = true, label = "Connected")
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Muted)
        }
    }
}

@Composable
private fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF171B30))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton(Screen.Dashboard, "Dashboard", current, onSelect)
        NavButton(Screen.Reports, "Reports", current, onSelect)
        NavButton(Screen.Logs, "Logs", current, onSelect)
    }
}

@Composable
private fun RowScope.NavButton(screen: Screen, label: String, current: Screen, onSelect: (Screen) -> Unit) {
    val active = current == screen
    val modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(12.dp))
        .clickable { onSelect(screen) }
        .padding(vertical = 9.dp)
    val bg = if (active) {
        Brush.horizontalGradient(listOf(Color(0xFFA9A2FF), Color(0xFF78D9FF)))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val fg = if (active) Color(0xFF11152B) else Muted
    val icon = when (screen) {
        Screen.Dashboard -> Icons.Filled.Dashboard
        Screen.Reports -> Icons.Filled.Timeline
        Screen.Logs -> Icons.Filled.Description
        Screen.Connect -> Icons.Filled.Dashboard
    }
    Box(modifier = modifier.background(bg, RoundedCornerShape(12.dp))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(21.dp))
            Text(label, color = fg, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
        }
    }
}