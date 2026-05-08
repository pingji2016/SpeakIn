package com.speakin.app.domain.asr

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsrEngineImpl @Inject constructor() : AsrEngine {

    private var modelLoaded: Boolean = false

    override fun transcribe(audioFile: File, callback: AsrEngine.Callback) {
        if (!modelLoaded) {
            callback.onError("ASR model not loaded")
            return
        }
        callback.onProgress(0f)
        callback.onResult("")
    }

    override fun isModelLoaded(): Boolean = modelLoaded

    fun loadModel() {
        modelLoaded = true
    }

    override fun release() {
        modelLoaded = false
    }
}
