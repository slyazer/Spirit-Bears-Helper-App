package com.macromonitor.app.domain

/**
 * Read-only presentation rules for the Roblox private-server population card.
 *
 * The bridge is the source of truth and already validates a sample before it is
 * stored, but the app still never trusts the numbers it renders: a missing or
 * out-of-range pair is treated as "no reading" rather than shown as a count.
 */
object PopulationStatus {

    /**
     * "playing/max" (e.g. "3/6"), or null when either value is missing or the
     * pair is not a valid population (negative, max below 1, or playing > max).
     */
    fun label(playing: Int?, max: Int?): String? {
        if (playing == null || max == null) return null
        if (max < 1 || playing < 0 || playing > max) return null
        return "$playing/$max"
    }

    /** True only when a fresh-enough, valid count should be shown as current. */
    fun isLive(available: Boolean, playing: Int?, max: Int?): Boolean =
        available && label(playing, max) != null
}
