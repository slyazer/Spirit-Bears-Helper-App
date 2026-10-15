package com.macromonitor.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macromonitor.app.R
import com.macromonitor.app.domain.ConnectionStatus
import com.macromonitor.app.net.DiscoveredBridge
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.Blue
import com.macromonitor.app.ui.theme.BrandEnd
import com.macromonitor.app.ui.theme.BrandMid
import com.macromonitor.app.ui.theme.BrandStart
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Red
import com.macromonitor.app.ui.theme.Text

private enum class ConnectMode { Manual, Nearby }

@Composable
fun ConnectScreen(
    connection: ConnectionStatus,
    message: String,
    initialBaseUrl: String,
    initialUsername: String,
    discovered: List<DiscoveredBridge>,
    discovering: Boolean,
    onLogin: (String, String, String) -> Unit,
    onScanQr: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onUseDiscovered: (DiscoveredBridge) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ConnectMode.Manual) }
    val busy = connection == ConnectionStatus.Checking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onScanQr,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan the QR code on your PC", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterChip(
                selected = mode == ConnectMode.Manual,
                onClick = { mode = ConnectMode.Manual },
                label = { Text("Enter details") },
                leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = mode == ConnectMode.Nearby,
                onClick = { mode = ConnectMode.Nearby },
                label = { Text("Find nearby") },
                leadingIcon = { Icon(Icons.Filled.WifiFind, contentDescription = null) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))

        when (mode) {
            ConnectMode.Manual -> ManualForm(
                baseUrl = baseUrl,
                username = username,
                password = password,
                busy = busy,
                onBaseUrl = { baseUrl = it },
                onUsername = { username = it },
                onPassword = { password = it },
                onSubmit = { onLogin(baseUrl, username, password) },
            )

            ConnectMode.Nearby -> NearbySection(
                discovered = discovered,
                discovering = discovering,
                busy = busy,
                onStart = onStartDiscovery,
                onStop = onStopDiscovery,
                onUse = onUseDiscovered,
            )
        }

        Spacer(Modifier.height(14.dp))
        statusMessage(connection, message)
    }
}

@Composable
private fun BrandHeader() {
    Box(
        modifier = Modifier
            .padding(top = 24.dp)
            .size(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(BrandStart, BrandMid, BrandEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_spirit_bear),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
    Text(
        "Spirit Bear's Helper App",
        style = MaterialTheme.typography.titleLarge,
        color = Text,
        modifier = Modifier.padding(top = 14.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "Sign in to the bridge running on your Windows PC",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ManualForm(
    baseUrl: String,
    username: String,
    password: String,
    busy: Boolean,
    onBaseUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 2.dp)) {
        Text("Bridge address", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrl,
            placeholder = { Text("http://192.168.1.100:8787") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(14.dp))
        Text("Username", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            placeholder = { Text("Your PC account name") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(14.dp))
        Text("Password", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            placeholder = { Text("Your password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = !busy && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF11152B),
                )
                Spacer(Modifier.width(8.dp))
                Text("Signing in…", fontWeight = FontWeight.Bold)
            } else {
                Text("Sign in", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NearbySection(
    discovered: List<DiscoveredBridge>,
    discovering: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUse: (DiscoveredBridge) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        OutlinedButton(
            onClick = { if (discovering) onStop() else onStart() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (discovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Accent,
                )
                Spacer(Modifier.width(8.dp))
                Text("Searching…")
            } else {
                Text("Search the local network")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (discovered.isEmpty()) {
            Text(
                if (discovering) "Looking for bridges on your Wi‑Fi… make sure the PC is running."
                else "No bridges found yet. Tap search while on the same Wi‑Fi as your PC.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        } else {
            discovered.forEach { bridge ->
                DiscoveredRow(bridge = bridge, onClick = { onUse(bridge) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DiscoveredRow(bridge: DiscoveredBridge, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B2140))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(bridge.name, color = Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(bridge.baseUrl, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text("Use", color = Accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF565C91),
    cursorColor = Accent,
)

@Composable
private fun statusMessage(connection: ConnectionStatus, message: String) {
    if (message.isBlank()) return
    val color = when (connection) {
        ConnectionStatus.Error -> Red
        ConnectionStatus.Connected -> Blue
        else -> Muted
    }
    Text(
        message,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
}
