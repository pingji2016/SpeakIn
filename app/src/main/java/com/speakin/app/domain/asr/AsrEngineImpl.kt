package com.speakin.app.domain.asr

import android.util.Log
import com.speakin.app.domain.model.AsrModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 引擎实现。
 *
 * 使用 ExecuTorch whisper 进行离线语音转文字。
 * 当模型未就绪时，降级为文件名模拟转写（调试用）。
 */
@Singleton
class AsrEngineImpl @Inject constructor(
    private val asrModelManager: AsrModelManager
) : AsrEngine {

    private var whisperEngine: ExecuTorchWhisperEngine? = null
    private var _modelLoaded: Boolean = false

    override fun isModelLoaded(): Boolean = _modelLoaded

    override fun transcribe(audioFile: File, callback: AsrEngine.Callback) {
        callback.onProgress(0f)

        if (!_modelLoaded) {
            Log.w(TAG, "Model not loaded, trying to load...")
            val loaded = loadEngine()
            if (!loaded) {
                Log.w(TAG, "Falling back to filename transcription")
                fallbackTranscribe(audioFile, callback)
                return
            }
        }

        GlobalScope.launch {
            try {
                doTranscribe(audioFile, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                callback.onError("转写失败: ${e.message}")
            }
        }
    }

    private fun loadEngine(): Boolean {
        val engine = ExecuTorchWhisperEngine()
        val modelDir = asrModelManager.getModelDir()

        if (!modelDir.exists()) {
            Log.w(TAG, "Model directory not found: ${modelDir.absolutePath}")
            return false
        }

        val success = engine.load(modelDir)
        if (success) {
            whisperEngine = engine
            _modelLoaded = true
            Log.i(TAG, "Whisper engine loaded successfully")
        } else {
            Log.e(TAG, "Failed to load whisper engine")
        }
        return success
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun doTranscribe(
        audioFile: File,
        callback: AsrEngine.Callback
    ) = withContext(Dispatchers.IO) {
        val engine = whisperEngine ?: return@withContext

        try {
            callback.onProgress(0.05f)

            val pcmData = readAudioFile(audioFile)
            if (pcmData == null) {
                callback.onError("无法读取音频文件")
                return@withContext
            }

            callback.onProgress(0.1f)

            val result = engine.transcribe(pcmData) { progress ->
                callback.onProgress(0.1f + progress * 0.9f)
            }

            if (result != null) {
                callback.onResult(result)
            } else {
                callback.onError("转写返回空结果")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            callback.onError("转写出错: ${e.message}")
        }
    }

    private fun readAudioFile(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            val numChannels = byteArrayToShort(bytes, 22).toInt()
            val sampleRate = byteArrayToInt(bytes, 24)
            val bitsPerSample = byteArrayToShort(bytes, 34).toInt()
            val dataSize = byteArrayToInt(bytes, 40)

            Log.d(TAG, "WAV: $numChannels ch, $sampleRate Hz, $bitsPerSample bit, $dataSize bytes data")

            val pcmStart = 44
            val sampleBytes = bitsPerSample / 8
            val numSamples = dataSize / sampleBytes

            val rawSamples = FloatArray(numSamples) { i ->
                when (bitsPerSample) {
                    16 -> {
                        val sampleIndex = pcmStart + i * 2
                        if (sampleIndex + 1 < bytes.size) {
                            (bytes[sampleIndex].toInt() and 0xFF or
                                (bytes[sampleIndex + 1].toInt() shl 8)).toShort().toFloat() / 32768f
                        } else 0f
                    }
                    8 -> {
                        val sampleIndex = pcmStart + i
                        if (sampleIndex < bytes.size) {
                            ((bytes[sampleIndex].toInt() and 0xFF) - 128).toFloat() / 128f
                        } else 0f
                    }
                    else -> 0f
                }
            }

            val mono: FloatArray = if (numChannels == 1) {
                rawSamples
            } else {
                FloatArray(numSamples / numChannels) { ch ->
                    var sum = 0f
                    for (c in 0 until numChannels) {
                        sum += rawSamples[ch * numChannels + c]
                    }
                    sum / numChannels
                }
            }

            if (sampleRate == 16000) mono else resample(mono, sampleRate, 16000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audio file", e)
            null
        }
    }

    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outputLength = (input.size * ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = i / ratio
            val left = srcIndex.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val frac = srcIndex - left
            output[i] = (input[left] * (1 - frac) + input[right] * frac).toFloat()
        }
        return output
    }

    private fun byteArrayToShort(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xFF) or
                (bytes[offset + 1].toInt() shl 8)).toShort()
    }

    private fun byteArrayToInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun fallbackTranscribe(audioFile: File, callback: AsrEngine.Callback) {
        callback.onProgress(0f)
        GlobalScope.launch {
            delay(500)
            callback.onProgress(0.5f)
            delay(500)
            callback.onResult("[模型未加载，使用文件名] ${audioFile.nameWithoutExtension}")
        }
    }

    override fun release() {
        whisperEngine?.release()
        whisperEngine = null
        _modelLoaded = false
    }

    companion object {
        private const val TAG = "AsrEngineImpl"
    }
}
