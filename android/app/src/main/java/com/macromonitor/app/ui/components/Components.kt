package com.macromonitor.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macromonitor.app.data.api.AccountSummaryDto
import com.macromonitor.app.ui.theme.Accent
import com.macromonitor.app.ui.theme.Border
import com.macromonitor.app.ui.theme.Green
import com.macromonitor.app.ui.theme.Muted
import com.macromonitor.app.ui.theme.Orange
import com.macromonitor.app.ui.theme.Panel
import com.macromonitor.app.ui.theme.Red
import com.macromonitor.app.ui.theme.Text

val CardShape = RoundedCornerShape(19.dp)
val SmallShape = RoundedCornerShape(12.dp)
val PillShape = RoundedCornerShape(999.dp)

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    gradient: Brush? = null,
    borderColor: Color = Border,
    content: @Composable () -> Unit,
) {
    val bg = gradient ?: Brush.linearGradient(listOf(Color(0xFF1A2035), Color(0xFF111727)))
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(bg)
            .border(1.dp, borderColor, CardShape)
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
fun CardHeader(
    title: String,
    subtitle: String? = null,
    tag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Text)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (tag != null) TinyTag(tag)
    }
}

@Composable
fun TinyTag(text: String, color: Color = Accent) {
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Border, PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Lets the user flip between monitored accounts (e.g. jidozacac_1 / slyazer). */
@Composable
fun AccountSwitcher(
    accounts: List<AccountSummaryDto>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accounts.size < 2) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        accounts.forEach { account ->
            val active = account.id == selected
            Box(
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (active) Accent.copy(alpha = 0.18f) else Panel)
                    .border(
                        1.dp,
                        if (active) Accent.copy(alpha = 0.8f) else Color(0xFF303958),
                        PillShape,
                    )
                    .clickable { onSelect(account.id) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        account.name,
                        color = if (active) Accent else Text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (account.commandAmbiguous) {
                        TinyTag("shared", color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val (color, label) = when (status) {
        "running" -> Green to "PLAYING"
        "paused" -> Orange to "PAUSED"
        "stopped" -> Red to "STOPPED"
        else -> Muted to "OFFLINE"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(Modifier.background(color, RoundedCornerShape(50)).padding(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun ConnectionIndicator(connected: Boolean, label: String) {
    val color = if (connected) Green else Muted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(PillShape)
            .background(Color(0xFF10131B))
            .border(1.dp, Border, PillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.background(color, RoundedCornerShape(50)).padding(3.dp))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = Muted)
    }
}

@Composable
fun AssetSprite(
    @DrawableRes icon: Int?,
    fallbackText: String,
    size: Dp = 40.dp,
    corner: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size * 0.09f),
            )
        } else {
            Text(
                fallbackText.take(1).uppercase(),
                color = Muted,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value / 2.4f).sp,
            )
        }
    }
}

@Composable
fun FieldTile(
    @DrawableRes icon: Int?,
    name: String,
    label: String? = null,
    subtitle: String? = null,
    timerText: String? = null,
    timerOver: Boolean = false,
    gradient: List<Color> = listOf(Color(0xFF252B4A), Color(0xFF19283A)),
    borderColor: Color = Color(0xFF3A4568),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.linearGradient(gradient))
            .border(1.dp, borderColor, CardShape)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8BE7FF).copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                color = Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (label != null) 3.dp else 0.dp),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8BE7FF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (timerText != null) {
            Text(
                timerText,
                color = if (timerOver) Orange else Muted,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
        if (icon != null) {
            AssetSprite(icon = icon, fallbackText = name, size = 60.dp)
        }
    }
}