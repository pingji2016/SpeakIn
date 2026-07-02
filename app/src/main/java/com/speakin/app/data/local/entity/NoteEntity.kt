package com.speakin.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val blockCount: Int = 0,
    val isPinned: Boolean = false,
    val contentJson: String? = null  // rich-document JSON (v4+); null = legacy blocks
)
