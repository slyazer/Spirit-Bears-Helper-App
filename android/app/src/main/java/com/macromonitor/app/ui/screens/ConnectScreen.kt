package com.macromonitor.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macromonitor.app.R
import com.macromonitor.app.domain.ConnectionStatus
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.Blue
import com.macromonitor.app.ui.theme.BrandEnd
import com.macromonitor.app.ui.theme.BrandMid
import com.macromonitor.app.ui.theme.BrandStart
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Red
import com.macromonitor.app.ui.theme.Text

@Composable
fun ConnectScreen(
    connection: ConnectionStatus,
    message: String,
    initialBaseUrl: String,
    onConnect: (String, String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    val busy = connection == ConnectionStatus.Checking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
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
            "Connect to your Windows bridge over Wi-Fi or 5G (Tailscale)",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(34.dp))

        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Text("Bridge address", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = { Text("http://100.x.y.z:8787") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color(0xFF565C91),
                    cursorColor = Accent,
                ),
            )
            Spacer(Modifier.height(14.dp))
            Text("API token", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = { Text("Token from MM_API_TOKEN") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color(0xFF565C91),
                    cursorColor = Accent,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onConnect(baseUrl, token) },
                enabled = !busy && baseUrl.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF11152B),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Auto: Wi‑Fi → 192.168.1.15:8787 · 5G/Tailscale → 100.116.124.113:8787",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
                } else {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            statusMessage(connection, message)
        }
    }
}

@Composable
private fun statusMessage(connection: ConnectionStatus, message: String) {
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