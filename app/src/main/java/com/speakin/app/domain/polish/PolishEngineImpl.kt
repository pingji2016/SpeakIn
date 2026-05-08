package com.speakin.app.domain.polish

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PolishEngineImpl @Inject constructor() : PolishEngine {

    private var modelLoaded: Boolean = false

    override fun polish(text: String, callback: PolishEngine.Callback) {
        if (!modelLoaded) {
            callback.onResult(text)
            return
        }
        callback.onResult(text)
    }

    override fun isModelLoaded(): Boolean = modelLoaded

    fun loadModel() {
        modelLoaded = true
    }

    override fun release() {
        modelLoaded = false
    }
}
