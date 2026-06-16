package com.speakin.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioOutputDir(@ApplicationContext context: Context): File {
        val dir = File(context.filesDir, "audio_records")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Provides
    @Singleton
    fun provideImageOutputDir(@ApplicationContext context: Context): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
