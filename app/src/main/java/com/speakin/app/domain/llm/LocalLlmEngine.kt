package com.speakin.app.domain.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var _isLoaded: Boolean = false

    init {
        System.loadLibrary("speakin_llama")
    }

    fun loadModel(modelFile: File): Boolean {
        if (_isLoaded) return true
        _isLoaded = nativeInit(modelFile.absolutePath)
        return _isLoaded
    }

    fun loadModelFromAssets(modelFileName: String): Boolean {
        val modelFile = copyModelFromAssets(modelFileName) ?: return false
        return loadModel(modelFile)
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

    private fun copyModelFromAssets(filename: String): File? {
        return try {
            val dest = File(context.filesDir, "models/$filename")
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open(filename).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeComplete(prompt: String): String?
    private external fun nativeRelease()
}
