package com.macromonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.macromonitor.app.data.api.LogEntryDto
import com.macromonitor.app.ui.Format
import com.macromonitor.app.ui.components.PanelCard
import com.macromonitor.app.ui.theme.Border
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Panel
import com.macromonitor.app.ui.theme.Text

@Composable
fun LogsScreen(
    logs: List<LogEntryDto>,
    detail: String?,
    imageUrl: (String?) -> String?,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Webhook logs",
                        style = MaterialTheme.typography.titleLarge,
                        color = Text,
                    )
                    Text(
                        "Discord-style event feed · newest first",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Muted)
                }
            }
        }
        if (logs.isEmpty()) {
            item {
                PanelCard {
                    Text("No log entries yet. Status embeds posted to the command channel will appear here.", color = Muted)
                }
            }
        } else {
            items(logs, key = { it.entryId }) { log ->
                LogCard(log, imageUrl)
            }
        }
        if (!detail.isNullOrBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF4A1018).copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(
                        "⚠ $detail",
                        color = Color(0xFFFFB3BC),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCard(log: LogEntryDto, imageUrl: (String?) -> String?) {
    val accent = parseColor(log.color) ?: Color(0xFF8BE7FF)
    val image = imageUrl(log.imageUrl)
    val title = log.title.ifBlank { log.body?.lineSequence()?.firstOrNull()?.take(80) ?: "Event" }
    val body = log.body?.takeIf { it.isNotBlank() && it != log.title && it != title }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Panel)
            .border(1.dp, Border, RoundedCornerShape(13.dp))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f).padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    title,
                    color = Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    Format.dateTime(log.timestamp),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (body != null) {
                Text(
                    body,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10162A))
                        .border(1.dp, Border, RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank() || !hex.startsWith("#")) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}
