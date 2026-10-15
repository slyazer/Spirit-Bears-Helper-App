package com.macromonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macromonitor.app.data.api.NectarDto
import com.macromonitor.app.data.api.PlanterDto
import com.macromonitor.app.data.api.PlantersResponseDto
import com.macromonitor.app.data.api.StatusDto
import com.macromonitor.app.domain.MacroCommands
import com.macromonitor.app.ui.Assets
import com.macromonitor.app.ui.Format
import com.macromonitor.app.ui.components.AssetSprite
import com.macromonitor.app.ui.components.CardHeader
import com.macromonitor.app.ui.components.FieldTile
import com.macromonitor.app.ui.components.PanelCard
import com.macromonitor.app.ui.components.StatusPill
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.Blue
import com.macromonitor.app.ui.theme.Green
import com.macromonitor.app.ui.theme.HoneyDark
import com.macromonitor.app.ui.theme.HoneyLight
import com.macromonitor.app.ui.theme.HoneyMid
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Orange
import com.macromonitor.app.ui.theme.Panel
import com.macromonitor.app.ui.theme.RateDark
import com.macromonitor.app.ui.theme.RateLight
import com.macromonitor.app.ui.theme.RateMid
import com.macromonitor.app.ui.theme.Red
import com.macromonitor.app.ui.theme.StatusDark
import com.macromonitor.app.ui.theme.StatusLight
import com.macromonitor.app.ui.theme.StatusMid
import com.macromonitor.app.ui.theme.Text
import java.time.OffsetDateTime
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    status: StatusDto?,
    planters: PlantersResponseDto?,
    commandHistory: List<Pair<String, String>>,
    commandBusy: Boolean,
    onCommand: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (status != null) {
            val tick = rememberTick()
            if (status.stale) {
                StaleBanner(lastUpdated = status.lastUpdated)
            }
            MetricCardRow(status)
            CurrentActivityCard(status, tick)
            PlantersCard(planters, tick)
            CommandConsole(
                onQuickCommand = onCommand,
                busy = commandBusy,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Text(
                        "Loading live data…",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StaleBanner(lastUpdated: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF4A2406).copy(alpha = 0.85f))
            .border(1.dp, Color(0xFFB06A18), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                "⚠ Macro not sending updates",
                color = Color(0xFFFFCF7F),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Last seen ${Format.relative(lastUpdated)} — the macro may be stopped or offline.",
                color = Color(0xFFFFE7C0),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MetricCardRow(status: StatusDto) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(
            leading = { AssetSprite(Assets.honey, "H", size = 60.dp, corner = 16.dp) },
            label = "Current honey",
            value = status.honeyTotal ?: "--",
            gradient = listOf(HoneyLight, HoneyMid, HoneyDark),
        )
        MetricCard(
            leading = { AssetSprite(Assets.honeyRate, "H", size = 60.dp, corner = 16.dp) },
            label = "Honey per hour",
            value = status.honeyPerHour ?: "--",
            gradient = listOf(RateLight, RateMid, RateDark),
        )
    }
}

@Composable
private fun MetricCard(
    leading: @Composable () -> Unit,
    label: String,
    value: String,
    gradient: List<Color>,
) {
    PanelCard(
        gradient = Brush.linearGradient(gradient),
        borderColor = gradient.last().copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-0.8).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            leading()
        }
    }
}

@Composable
private fun CurrentActivityCard(status: StatusDto, tick: Int) {
    val activity = status.activity
    val field = status.field
    // Nothing to show (e.g. stopped, or stale and we dropped the card data).
    if (activity == null && field == null) return

    val gathering = activity?.equals("Gathering", ignoreCase = true) == true
    val activityGradient = listOf(Color(0xFF3A4E9E), Color(0xFF2A3A79), Color(0xFF1C2650))
    val activityBorder = Color(0xFF4A5FA8)
    when {
        // Gathering a field: show the field with its live timer.
        gathering && field != null -> FieldTile(
            icon = Assets.activity(field),
            name = field,
            label = "Current activity",
            subtitle = if (status.fieldBoosted == true) "BOOSTED" else null,
            timerText = fieldTimer(status, tick),
            timerOver = fieldTimerOver(status),
            gradient = activityGradient,
            borderColor = activityBorder,
        )
        // Doing something else (converting, traveling, resetting, …): show
        // the action itself so the card never goes blank while the macro is
        // busy at the hive.
        activity != null -> FieldTile(
            icon = Assets.activityIcon(activity),
            name = activity,
            label = "Current activity",
            subtitle = status.activityDetail,
            gradient = activityGradient,
            borderColor = activityBorder,
        )
        // Paused mid-field, or an activity we could not classify.
        field != null -> FieldTile(
            icon = Assets.activity(field),
            name = field,
            label = "Current activity",
            subtitle = if (status.fieldBoosted == true) "BOOSTED" else null,
            gradient = activityGradient,
            borderColor = activityBorder,
        )
    }
}

/** Elapsed seconds since an ISO timestamp, or null when unknown. */
private fun elapsedSince(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).seconds
    } catch (e: Exception) {
        null
    }
}

private fun fieldTimer(status: StatusDto, @Suppress("UNUSED_PARAMETER") tick: Int): String {
    val elapsed = elapsedSince(status.fieldSince) ?: return ""
    val limit = status.fieldLimitSeconds
    return if (limit != null) {
        "${Format.clock(elapsed)} / ${Format.clock(limit.toLong())}"
    } else {
        Format.clock(elapsed)
    }
}

private fun fieldTimerOver(status: StatusDto): Boolean {
    val elapsed = elapsedSince(status.fieldSince) ?: return false
    val limit = status.fieldLimitSeconds ?: return false
    return elapsed > limit
}

@Composable
private fun PlantersCard(planters: PlantersResponseDto?, tick: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        CardHeader(
            title = "Planters",
            tag = if (planters == null) "…" else "${planters.planters.size} SLOTS",
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val slots = planters?.planters.orEmpty()
            for (i in 1..3) {
                val p = slots.getOrNull(i - 1)
                PlanterChip(p ?: PlanterDto(slot = i), tick)
            }
        }
        NectarSection(planters?.nectars.orEmpty())
    }
}

/** The five BSS nectar levels, read from the patched StatMonitor OCR. */
@Composable
private fun NectarSection(nectars: List<NectarDto>) {
    val shown = nectars.filter { it.level != null }
    if (shown.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "Nectar",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        shown.forEach { n ->
            val color = Assets.nectarColor(n.name)
            val level = (n.level ?: 0.0).coerceIn(0.0, 100.0).toFloat()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    n.name.take(3),
                    color = Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(30.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF232B44)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level / 100f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(color),
                    )
                }
                Text(
                    "${level.roundToInt()}%",
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(38.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.PlanterChip(p: PlanterDto, tick: Int) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .background(Panel)
            .border(1.dp, Color(0xFF303958), RoundedCornerShape(13.dp))
            .padding(horizontal = 5.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AssetSprite(
            icon = Assets.planter(p.name),
            fallbackText = p.name ?: "?",
            size = 48.dp,
            corner = 12.dp,
        )
        Text(
            Format.planterName(p.name),
            color = Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            etaLabel(p, tick),
            color = if (p.harvestEta != null) Green else Muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun etaLabel(p: PlanterDto, @Suppress("UNUSED_PARAMETER") tick: Int): String {
    val eta = p.harvestEta ?: return if (p.field != null) Format.percent(p.estPercent) else ""
    return try {
        val parsed = OffsetDateTime.parse(eta)
        val until = java.time.Duration.between(OffsetDateTime.now(), parsed).seconds
        if (until <= 0) "Ready!" else "${until / 3600}h ${(until % 3600) / 60}m"
    } catch (e: Exception) {
        ""
    }
}

@Composable
private fun CommandConsole(
    onQuickCommand: (String) -> Unit,
    busy: Boolean,
) {
    var pending by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MacroCommands.QUICK.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { cmd ->
                    CommandButton(cmd, enabled = !busy) { pending = cmd }
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    val command = pending
    if (command != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Send command?") },
            text = { Text("Are you sure you want to send \u201C$command\u201D to this account?") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onQuickCommand(command)
                }) {
                    Text("Send", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text("Cancel", color = Muted)
                }
            },
        )
    }
}

@Composable
private fun RowScope.CommandButton(cmd: String, enabled: Boolean, onClick: () -> Unit) {
    val color = commandColor(cmd)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(46.dp),
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.22f),
            contentColor = color,
            disabledContainerColor = Panel,
            disabledContentColor = Muted,
        ),
    ) {
        Text(
            cmd,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun commandColor(cmd: String): Color = when (cmd.removePrefix("?").lowercase()) {
    "start", "resume" -> Green
    "stop" -> Red
    "pause" -> Orange
    "timers" -> Blue
    else -> Accent
}

/** Recomposition ticker: bumps once per second so live timers/numbers update. */
@Composable
private fun rememberTick(periodMs: Long = 1000L): Int {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(periodMs)
            tick++
        }
    }
    return tick
}