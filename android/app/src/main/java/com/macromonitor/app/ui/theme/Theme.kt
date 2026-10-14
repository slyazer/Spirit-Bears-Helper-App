package com.macromonitor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MmColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF11152B),
    secondary = Blue,
    onSecondary = Color(0xFF082B48),
    background = Bg,
    onBackground = Text,
    surface = Panel,
    onSurface = Text,
    surfaceVariant = Panel2,
    onSurfaceVariant = Muted,
    outline = Border,
    error = Red,
    onError = Bg,
)

@Composable
fun MacroMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MmColorScheme,
        typography = Typography,
        content = content,
    )
}