package com.speakin.app.data.local.entity

import kotlinx.serialization.Serializable

/**
 * A segment of rich content within a note — can be styled text, an inline image,
 * or an inline audio clip.  The whole document is an ordered list of these.
 */
@Serializable
sealed class RichSegment {

    /** Plain or styled text run. */
    @Serializable
    data class Text(
        val text: String,
        val spans: List<SpanInfo> = emptyList()
    ) : RichSegment()

    /** An image embedded inline in the text flow. */
    @Serializable
    data class Image(
        val imagePath: String,
        val altText: String = ""
    ) : RichSegment()

    /** An audio clip embedded inline (recorded via the mic). */
    @Serializable
    data class Audio(
        val audioPath: String,
        val durationMs: Long,
        val transcription: String? = null,
        val polishedText: String? = null
    ) : RichSegment()
}

/** Describes a formatting span over a [start, end) character range. */
@Serializable
data class SpanInfo(
    val type: SpanType,
    val start: Int,
    val end: Int
)

@Serializable
enum class SpanType {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    HEADING
}
