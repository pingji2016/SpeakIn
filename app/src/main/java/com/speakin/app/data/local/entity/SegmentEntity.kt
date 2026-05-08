package com.speakin.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId")]
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val audioFilePath: String,
    val durationMs: Long,
    val rawText: String = "",
    val polishedText: String = "",
    val createdAt: Long,
    val sortOrder: Int
)
