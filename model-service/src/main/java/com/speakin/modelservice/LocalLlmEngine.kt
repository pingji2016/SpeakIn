package com.speakin.modelservice

import android.util.Log
import java.io.File

/**
 * LLM 引擎 JNI 封装。
 * 通过 JNI 调用 llama.cpp C++ 代码进行本地推理。
 * 运行在 :model 进程中。
 */
class LocalLlmEngine {

    private var _isLoaded = false

    init {
        System.loadLibrary("speakin_llama")
    }

    fun loadModel(modelFile: File): Boolean {
        if (_isLoaded) return true
        _isLoaded = nativeInit(modelFile.absolutePath)
        return _isLoaded
    }

    fun complete(prompt: String): String {
        if (!_isLoaded) return ""
        return nativeComplete(prompt) ?: ""
    }

    fun release() {
        if (_isLoaded) {
            nativeRelease()
            _isLoaded = false
        }
    }

    val isLoaded: Boolean get() = _isLoaded

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeComplete(prompt: String): String?
    private external fun nativeRelease()
}
