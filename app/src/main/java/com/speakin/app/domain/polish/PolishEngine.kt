package com.speakin.app.domain.polish

interface PolishEngine {

    interface Callback {
        fun onResult(text: String)
        fun onError(error: String)
    }

    fun polish(text: String, callback: Callback)

    fun isModelLoaded(): Boolean

    fun release()
}
