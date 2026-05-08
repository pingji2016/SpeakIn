package com.speakin.app.domain.asr

import java.io.File

interface AsrEngine {

    interface Callback {
        fun onResult(text: String)
        fun onProgress(progress: Float)
        fun onError(error: String)
    }

    fun transcribe(audioFile: File, callback: Callback)

    fun isModelLoaded(): Boolean

    fun release()
}
