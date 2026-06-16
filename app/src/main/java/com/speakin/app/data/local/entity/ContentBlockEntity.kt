package com.speakin.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "content_blocks",
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
data class ContentBlockEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val blockType: BlockType,
    val textContent: String = "",
    val audioFilePath: String? = null,
    val durationMs: Long? = null,
    val transcription: String? = null,
    val polishedText: String? = null,
    val imageFilePath: String? = null,
    val sortOrder: Int,
    val createdAt: Long
)
