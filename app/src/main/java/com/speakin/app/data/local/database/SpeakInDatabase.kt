package com.speakin.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.speakin.app.data.local.dao.ContentBlockDao
import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.entity.ContentBlockEntity
import com.speakin.app.data.local.entity.NoteEntity

@Database(
    entities = [NoteEntity::class, ContentBlockEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SpeakInDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun contentBlockDao(): ContentBlockDao
}
