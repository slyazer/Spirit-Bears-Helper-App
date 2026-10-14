package com.macromonitor.app.domain

/** Client-side mirror of the bridge's command allowlist. */
object MacroCommands {
    val ALLOWED: Set<String> = setOf(
        "start", "stop", "pause", "resume", "close", "planters", "timers", "status", "help",
    )

    val QUICK: List<String> = listOf(
        "?start", "?stop", "?pause", "?resume", "?close", "?timers",
    )

    /** Returns the normalized command (without prefix) or null if not allowed. */
    fun normalize(input: String): String? {
        val body = input.trim().removePrefix("?").lowercase()
        return if (body in ALLOWED) body else null
    }
}

enum class ConnectionStatus { Disconnected, Checking, Connected, Error }
