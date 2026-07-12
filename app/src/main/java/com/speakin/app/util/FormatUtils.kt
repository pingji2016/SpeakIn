package com.speakin.app.util

import com.speakin.app.data.local.entity.RichSegment
import kotlinx.serialization.json.Json

/**
 * Shared formatting utilities used across the app.
 */
object FormatUtils {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

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

    /**
     * Extract a plain-text preview from a note's rich-content JSON.
     * Returns the first [maxLength] characters of concatenated segment text,
     * or an empty string if the content is empty or unparseable.
     */
    fun extractContentPreview(contentJson: String?, maxLength: Int = 10): String {
        if (contentJson.isNullOrBlank()) return ""
        return try {
            val segments = json.decodeFromString<List<RichSegment>>(contentJson)
            segments.joinToString(" ") { seg ->
                when (seg) {
                    is RichSegment.Text -> seg.text
                    is RichSegment.Audio -> seg.polishedText?.takeIf { it.isNotBlank() }
                        ?: seg.transcription?.takeIf { it.isNotBlank() } ?: ""
                    is RichSegment.Image -> seg.altText
                }
            }.trim().take(maxLength)
        } catch (_: Exception) {
            ""
        }
    }
}
