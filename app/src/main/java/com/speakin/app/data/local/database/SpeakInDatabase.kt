package com.speakin.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.dao.SegmentDao
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.SegmentEntity

@Database(
    entities = [NoteEntity::class, SegmentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SpeakInDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun segmentDao(): SegmentDao
}
