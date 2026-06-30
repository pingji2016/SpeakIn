package com.speakin.app.di

import android.content.Context
import androidx.room.Room
import com.speakin.app.data.local.dao.ContentBlockDao
import com.speakin.app.data.local.dao.NoteDao
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
        )
            .addMigrations(SpeakInDatabase.MIGRATION_2_3, SpeakInDatabase.MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideNoteDao(database: SpeakInDatabase): NoteDao = database.noteDao()

    @Provides
    fun provideContentBlockDao(database: SpeakInDatabase): ContentBlockDao =
        database.contentBlockDao()
}
