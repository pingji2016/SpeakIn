package com.speakin.modelservice

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * 模型服务 — 运行在独立进程 :model 中。
 *
 * 所有 LLM / ASR 模型推理都在此进程执行。
 * 即使此处崩溃，主进程 UI 不受影响（系统会自动重启此服务）。
 */
class ModelService : Service() {

    private val llmEngine = LocalLlmEngine()
    private val asrEngine = ExecuTorchWhisperEngine()
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val binder = object : IModelService.Stub() {

        // ─── LLM ────────────────────────────────────────

        override fun loadLlmModel(modelPath: String): Boolean {
            Log.i(TAG, "LLM load: $modelPath")
            return llmEngine.loadModel(File(modelPath))
        }

        override fun complete(prompt: String): String {
            Log.i(TAG, "LLM complete, prompt len=${prompt.length}")
            return llmEngine.complete(prompt)
        }

        override fun isLlmLoaded(): Boolean = llmEngine.isLoaded

        // ─── ASR ────────────────────────────────────────

        override fun loadAsrModel(asrDir: String): Boolean {
            Log.i(TAG, "ASR load: $asrDir")
            return asrEngine.load(File(asrDir))
        }

        override fun transcribe(audioPath: String, callback: IModelServiceCallback?) {
            val cb = callback ?: return

            workerHandler?.post {
                try {
                    Log.i(TAG, "ASR transcribe: $audioPath")

                    // 读取 WAV → PCM
                    val pcmData = readWavAsFloat(File(audioPath))
                    if (pcmData == null) {
                        cb.onError("无法读取音频文件")
                        return@post
                    }

                    cb.onProgress(0.1f)

                    // 执行转写
                    val result = asrEngine.transcribe(pcmData) { progress ->
                        cb.onProgress(0.1f + progress * 0.9f)
                    }

                    if (result != null) cb.onResult(result)
                    else cb.onError("转写失败")

                } catch (e: Exception) {
                    Log.e(TAG, "Transcribe error", e)
                    cb.onError(e.message ?: "未知错误")
                }
            }
        }

        override fun isAsrLoaded(): Boolean = asrEngine.isLoaded

        // ─── 生命周期 ────────────────────────────────────

        override fun release() {
            Log.i(TAG, "Releasing all engines")
            llmEngine.release()
            asrEngine.release()
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("model-worker").apply { start() }
        workerHandler = Handler(worker!!.looper)
        Log.i(TAG, "ModelService created (PID=${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        llmEngine.release()
        asrEngine.release()
        worker?.quitSafely()
        worker = null
        Log.i(TAG, "ModelService destroyed")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════
    // WAV 读取
    // ═══════════════════════════════════════════════════════════

    private fun readWavAsFloat(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            val numChannels = shortAt(bytes, 22).toInt()
            val sampleRate = intAt(bytes, 24)
            val bitsPerSample = shortAt(bytes, 34).toInt()
            val dataSize = intAt(bytes, 40)

            val pcmStart = 44
            val sampleBytes = bitsPerSample / 8
            val numSamples = dataSize / sampleBytes

            val raw = FloatArray(numSamples) { i ->
                when (bitsPerSample) {
                    16 -> {
                        val si = pcmStart + i * 2
                        if (si + 1 < bytes.size)
                            (bytes[si].toInt() and 0xFF or (bytes[si + 1].toInt() shl 8))
                                .toShort().toFloat() / 32768f
                        else 0f
                    }
                    8 -> {
                        val si = pcmStart + i
                        if (si < bytes.size) ((bytes[si].toInt() and 0xFF) - 128).toFloat() / 128f
                        else 0f
                    }
                    else -> 0f
                }
            }

            val mono = if (numChannels == 1) raw
            else FloatArray(numSamples / numChannels) { ch ->
                var sum = 0f
                for (c in 0 until numChannels) sum += raw[ch * numChannels + c]
                sum / numChannels
            }

            if (sampleRate == 16000) mono else resample(mono, sampleRate, 16000)
        } catch (e: Exception) {
            Log.e(TAG, "readWav failed", e)
            null
        }
    }

    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val n = (input.size * ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val si = i / ratio
            val l = si.toInt(); val r = (l + 1).coerceAtMost(input.size - 1)
            val f = si - l
            out[i] = (input[l] * (1 - f) + input[r] * f).toFloat()
        }
        return out
    }

    private fun shortAt(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort()

    private fun intAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val TAG = "ModelService"
    }
}
