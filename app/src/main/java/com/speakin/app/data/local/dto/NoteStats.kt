package com.speakin.app.data.local.dto

data class NoteStats(
    val noteId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val blockCount: Int,
    val textBlockCount: Int = 0,
    val voiceBlockCount: Int = 0,
    val imageBlockCount: Int = 0,
    val totalAudioDurationMs: Long = 0L,
    val totalTextLength: Int = 0,
    val usesRichContent: Boolean = false  // true = v4+ rich format
)
