package com.speakin.app.domain.asr

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsrEngineImpl @Inject constructor() : AsrEngine {

    private var _modelLoaded: Boolean = false

    override fun transcribe(audioFile: File, callback: AsrEngine.Callback) {
        if (!_modelLoaded) {
            simulateTranscription(audioFile, callback)
            return
        }
        simulateTranscription(audioFile, callback)
    }

    private fun simulateTranscription(audioFile: File, callback: AsrEngine.Callback) {
        val fileName = audioFile.nameWithoutExtension
        callback.onProgress(0f)
        kotlinx.coroutines.GlobalScope.launch {
            delay(1500)
            callback.onProgress(0.5f)
            delay(1000)
            callback.onResult("")
            _modelLoaded = true
        }
    }

    override fun isModelLoaded(): Boolean = _modelLoaded

    override fun release() {
        _modelLoaded = false
    }
}
