package com.speakin.app.data.local.dto

data class NoteStats(
    val noteId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val blockCount: Int,
    val textBlockCount: Int,
    val voiceBlockCount: Int,
    val imageBlockCount: Int,
    val totalAudioDurationMs: Long,
    val totalTextLength: Int
)
