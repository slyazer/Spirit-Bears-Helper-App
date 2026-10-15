package com.macromonitor.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.macromonitor.app.data.api.ReportImageDto
import com.macromonitor.app.ui.Format
import com.macromonitor.app.ui.components.CardHeader
import com.macromonitor.app.ui.components.PanelCard
import com.macromonitor.app.ui.components.TinyTag
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.Blue
import com.macromonitor.app.ui.theme.Border
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Panel2
import com.macromonitor.app.ui.theme.Text

@Composable
fun ReportsScreen(
    reports: List<ReportImageDto>,
    detail: String?,
    selected: ReportImageDto?,
    imageUrl: (String?) -> String?,
    onOpen: (ReportImageDto) -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (selected != null) {
        ReportDetail(selected, imageUrl, onBack = onClose)
        return
    }
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
                Text(
                    "Hourly reports",
                    style = MaterialTheme.typography.titleLarge,
                    color = Text,
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Muted)
                }
            }
            Text(
                "Newest first · tap any entry for the report image",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (reports.isEmpty()) {
            item {
                PanelCard {
                    Text("No reports yet. The macro posts hourly reports to its Discord channel.", color = Muted)
                }
            }
        } else {
            items(reports, key = { it.reportId }) { r ->
                ReportRow(r, onClick = { onOpen(r) })
            }
        }
        if (!detail.isNullOrBlank()) {
            item {
                ErrorBanner(detail)
            }
        }
    }
}

@Composable
internal fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF4A1018).copy(alpha = 0.85f))
            .border(1.dp, Color(0xFFC04456), RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text(
            "⚠ $message",
            color = Color(0xFFFFB3BC),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReportRow(report: ReportImageDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF1B2339), Color(0xFF151A2B))), RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Format.time(report.arrivalTime),
                color = Text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                report.summary ?: Format.dateTime(report.arrivalTime),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text("›", color = Accent, fontSize = 26.sp)
    }
}

@Composable
private fun ReportDetail(
    report: ReportImageDto,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
) {
    var showZoom by remember { mutableStateOf(false) }
    val url = imageUrl(report.imageProxyUrl)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Text)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Report · ${Format.time(report.arrivalTime)}",
                        color = Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Arrived ${Format.dateTime(report.arrivalTime)}",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            PanelCard {
                CardHeader(title = "Hourly summary", subtitle = report.summary ?: "Report image", tag = "HOURLY")
                if (url != null) {
                    Spacer(Modifier.height(14.dp))
                    AsyncImage(
                        model = url,
                        contentDescription = "Hourly report",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showZoom = true }
                            .background(Color(0xFF10162A))
                            .border(1.dp, Border, RoundedCornerShape(14.dp)),
                    )
                    Text(
                        "Tap the image to zoom",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text("Report image unavailable.", color = Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Arrival", Format.time(report.arrivalTime), Modifier.weight(1f))
                StatTile("Author", report.author ?: "--", Modifier.weight(1f))
            }
        }

        if (showZoom && url != null) {
            ZoomOverlay(url = url, onClose = { showZoom = false })
        }
    }
}

@Composable
private fun ZoomOverlay(url: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2050711))
            .clickable(onClick = onClose),
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Zoomed report",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClose() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
        )
        Text(
            "Pinch to zoom · double-tap to toggle · tap to close",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
        )
        FloatingActionButton(
            onClick = onClose,
            containerColor = Panel2,
            contentColor = Text,
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
    }
}