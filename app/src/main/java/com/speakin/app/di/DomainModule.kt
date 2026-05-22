package com.speakin.app.di

import com.speakin.app.domain.asr.AsrEngine
import com.speakin.app.domain.asr.AsrEngineImpl
import com.speakin.app.domain.polish.PolishEngine
import com.speakin.app.domain.polish.PolishEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindAsrEngine(impl: AsrEngineImpl): AsrEngine

    @Binds
    @Singleton
    abstract fun bindPolishEngine(impl: PolishEngineImpl): PolishEngine
}
