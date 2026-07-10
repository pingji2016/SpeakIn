package com.speakin.app.util

/**
 * Shared formatting utilities used across the app.
 */
object FormatUtils {

    /**
     * Format milliseconds as "m:ss".
     * Examples: 65000 → "1:05", 30000 → "0:30"
     */
    fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return "%d:%02d".format(minutes, secs)
    }

    /**
     * Format milliseconds as human-readable duration.
     * Examples: 125000 → "2m 5s", 30000 → "30s"
     */
    fun formatDurationHuman(totalMs: Long): String {
        val totalSeconds = totalMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
