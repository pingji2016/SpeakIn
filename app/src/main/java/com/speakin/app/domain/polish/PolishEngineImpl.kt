package com.speakin.app.domain.polish

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PolishEngineImpl @Inject constructor() : PolishEngine {

    override fun polish(text: String, callback: PolishEngine.Callback) {
        kotlinx.coroutines.GlobalScope.launch {
            delay(500)
            callback.onResult(text)
        }
    }

    override fun isModelLoaded(): Boolean = true

    override fun release() {}
}
