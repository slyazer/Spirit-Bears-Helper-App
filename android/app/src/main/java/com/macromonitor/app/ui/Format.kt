package com.macromonitor.app.ui

import java.text.SimpleDateFormat
import java.util.Locale

object Format {

    fun stateLabel(state: String): String = when (state) {
        "running" -> "Playing"
        "paused" -> "Paused"
        "stopped" -> "Stopped"
        else -> "Unknown"
    }

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "--"
        val parts = mutableListOf<String>()
        var rest = seconds
        val days = rest / 86400; rest %= 86400
        val hours = rest / 3600; rest %= 3600
        val mins = rest / 60
        if (days > 0) parts.add("${days}d")
        if (hours > 0 || days > 0) parts.add("${hours}h")
        if (mins > 0 || (hours == 0L && days == 0L)) parts.add("${mins}m")
        return parts.joinToString(" ")
    }

    fun percent(value: Double?): String = if (value == null) "--" else "${"%.1f".format(value)}%"

    /** Turn Natro's compact planter ids into a readable label ("BlueClayPlanter" -> "Blue Clay"). */
    fun planterName(raw: String?): String {
        if (raw.isNullOrBlank()) return "Empty"
        if (raw.contains("ofplenty", ignoreCase = true)) return "Planter of Plenty"
        val stripped = raw.replace("Planter", "", ignoreCase = true).trim()
        return stripped.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").ifBlank { "Empty" }
    }

    /** M:SS / H:MM:SS clock used for the in-field timer. */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun time(iso: String?): String {
        if (iso.isNullOrBlank()) return "--"
        return try {
            val parsed = java.time.OffsetDateTime.parse(iso)
            val local = parsed.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
            "%02d:%02d".format(local.hour, local.minute)
        } catch (e: Exception) {
            iso
        }
    }

    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "--"
        return try {
            val parsed = java.time.OffsetDateTime.parse(iso)
            val local = parsed.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
            "%02d/%02d %02d:%02d".format(local.monthValue, local.dayOfMonth, local.hour, local.minute)
        } catch (e: Exception) {
            iso
        }
    }

    fun relative(iso: String?): String {
        if (iso.isNullOrBlank()) return "--"
        return try {
            val parsed = java.time.OffsetDateTime.parse(iso)
            val elapsed = java.time.Duration.between(parsed, java.time.OffsetDateTime.now())
            val s = elapsed.seconds
            when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60}m ago"
                s < 86400 -> "${s / 3600}h ago"
                else -> "${s / 86400}d ago"
            }
        } catch (e: Exception) {
            iso
        }
    }
}