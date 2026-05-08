package com.speakin.app.di

import android.content.Context
import androidx.room.Room
import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.dao.SegmentDao
import com.speakin.app.data.local.database.SpeakInDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpeakInDatabase {
        return Room.databaseBuilder(
            context,
            SpeakInDatabase::class.java,
            "speakin_database"
        ).build()
    }

    @Provides
    fun provideNoteDao(database: SpeakInDatabase): NoteDao = database.noteDao()

    @Provides
    fun provideSegmentDao(database: SpeakInDatabase): SegmentDao = database.segmentDao()
}
