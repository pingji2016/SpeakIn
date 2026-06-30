package com.speakin.app.domain.asr

import android.util.Log
import com.speakin.app.domain.audio.AudioBuffer
import com.speakin.app.domain.service.ModelServiceFacade
import com.speakin.app.domain.model.AsrModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 引擎实现 — 通过 AIDL 调用远程 :model 进程。
 *
 * 模型推理在独立进程中执行，崩溃不影响 UI。
 */
@Singleton
class AsrEngineImpl @Inject constructor(
    private val modelService: ModelServiceFacade,
    private val asrModelManager: AsrModelManager
) : AsrEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun isModelLoaded(): Boolean {
        return asrModelManager.isModelReady()
    }

    override fun transcribe(audioFile: File, callback: AsrEngine.Callback) {
        callback.onProgress(0f)

        scope.launch {
            try {
                if (!asrModelManager.isModelReady()) {
                    callback.onError("ASR 模型未就绪")
                    return@launch
                }

                modelService.bind()
                val loaded = modelService.loadAsr(asrModelManager.getModelDir())
                if (!loaded) {
                    callback.onError("ASR 模型加载失败")
                    return@launch
                }

                callback.onProgress(0.05f)
                val result = modelService.transcribe(audioFile) { progress ->
                    callback.onProgress(progress)
                }

                result.fold(
                    onSuccess = { text -> callback.onResult(text) },
                    onFailure = { e -> callback.onError(e.message ?: "转写失败") }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcribe failed", e)
                callback.onError("转写出错: ${e.message}")
            }
        }
    }

    // ─── 流式 API ───────────────────────────────────────────

    override fun startStreaming(callback: AsrEngine.StreamingCallback): StreamingAsrSession {
        // 只允许一个流式会话
        currentStreamingSession?.cancel()
        val session = StreamingAsrSessionImpl(callback)
        currentStreamingSession = session
        session.start()
        return session
    }

    private var currentStreamingSession: StreamingAsrSessionImpl? = null

    override fun release() {
        currentStreamingSession?.cancel()
        currentStreamingSession = null
    }

    /**
     * 流式识别会话实现。
     *
     * 核心策略：
     * 1. AudioBuffer 持续累积音频
     * 2. 每累积 ~2 秒新音频触发一次识别
     * 3. 如果上一次识别还在跑，跳过本次（避免堆积）
     * 4. 每次识别结果通过 ResultRefiner 对比上次，产出稳定/变化标记
     * 5. finish() 时等待当前推理完成，然后跑最终完整识别
     */
    private inner class StreamingAsrSessionImpl(
        private val callback: AsrEngine.StreamingCallback
    ) : StreamingAsrSession {

        private val audioBuffer = AudioBuffer()
        private val refiner = ResultRefiner()
        private val recognitionIntervalMs = 2000L       // 每 2 秒触发一次识别
        private val minSamplesForRecognition = 16000     // 至少 1 秒音频才开始识别
        private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var drainJob: Job? = null
        private var lastRecognitionTime = 0L
        private var running = AtomicBoolean(false)
        private var cancelled = AtomicBoolean(false)
        private var finishing = AtomicBoolean(false)
        private var lastDrainSampleCount = 0

        @Volatile
        override var isRunning: Boolean = false

        fun start() {
            isRunning = true

            // 启动 drain 循环
            drainJob = sessionScope.launch {
                // 先确保模型就绪
                try {
                    modelService.bind()
                    val loaded = modelService.loadAsr(asrModelManager.getModelDir())
                    if (!loaded) {
                        callback.onError("ASR 模型加载失败")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming init failed", e)
                    callback.onError("流式识别初始化失败: ${e.message}")
                    return@launch
                }

                while (isActive && !cancelled.get()) {
                    delay(500) // 每 500ms 检查一次

                    if (cancelled.get()) break

                    val currentSamples = audioBuffer.totalSamples
                    val newSamples = currentSamples - lastDrainSampleCount
                    val now = System.currentTimeMillis()
                    val hasEnoughAudio = currentSamples >= minSamplesForRecognition
                    val hasNewAudio = newSamples >= 3200 // 至少 200ms 新音频
                    val hasEnoughInterval = now - lastRecognitionTime >= recognitionIntervalMs
                    val notAlreadyRunning = !running.get()
                    val isFinishing = finishing.get()

                    if (hasEnoughAudio && hasNewAudio && hasEnoughInterval && notAlreadyRunning) {
                        lastDrainSampleCount = currentSamples
                        triggerRecognition(isFinal = false)
                    }

                    // finishing 且没有正在运行的推理时，触发最终识别
                    if (isFinishing && !running.get() && !cancelled.get()) {
                        triggerRecognition(isFinal = true)
                        break
                    }
                }
            }
        }

        override fun feedAudio(chunk: ShortArray) {
            if (cancelled.get() || finishing.get()) return
            audioBuffer.append(chunk)
        }

        override fun finish() {
            if (cancelled.get()) return
            finishing.set(true)
        }

        override fun cancel() {
            cancelled.set(true)
            running.set(false)
            audioBuffer.reset()
            refiner.reset()
            drainJob?.cancel()
            modelService.cancelTranscribe()
            isRunning = false
            if (currentStreamingSession === this) {
                currentStreamingSession = null
            }
        }

        private fun triggerRecognition(isFinal: Boolean) {
            if (cancelled.get()) return
            running.set(true)
            isRunning = true
            lastRecognitionTime = System.currentTimeMillis()

            sessionScope.launch {
                try {
                    val result: Result<String> = if (isFinal) {
                        // Final: save entire buffer to temp WAV file, transcribe via file path
                        // to avoid TransactionTooLargeException (PCM FloatArray > 1MB Binder limit)
                        val tempFile = java.io.File(
                            asrModelManager.getModelDir().parentFile,
                            "stream_final_${System.currentTimeMillis()}.wav"
                        )
                        try {
                            writeFloatArrayAsWav(audioBuffer.toFloatArrayFull(), tempFile)
                            modelService.transcribe(tempFile)
                        } finally {
                            tempFile.delete()
                        }
                    } else {
                        // Partial: only send last ~5 seconds (80000 samples * 4 bytes = 320KB)
                        // which stays well under the 1MB Binder transaction limit
                        val tailSamples = 80000  // 5 seconds @ 16kHz
                        val audioData = audioBuffer.toFloatArrayTail(tailSamples)
                        modelService.transcribeAudioData(audioData)
                    }

                    result.fold(
                        onSuccess = { text ->
                            if (cancelled.get()) return@launch

                            if (isFinal) {
                                callback.onFinalResult(text)
                            } else {
                                val refined = refiner.refine(text)
                                callback.onPartialResult(refined)
                            }
                        },
                        onFailure = { e ->
                            if (!cancelled.get()) {
                                Log.w(TAG, "Streaming recognition failed: ${e.message}")
                                callback.onError(e.message ?: "转写失败")
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (!cancelled.get()) {
                        Log.e(TAG, "Streaming recognition error", e)
                        callback.onError("转写出错: ${e.message}")
                    }
                } finally {
                    running.set(false)
                    isRunning = false

                    // 如果是最终识别，清理自身
                    if (isFinal) {
                        if (currentStreamingSession === this@StreamingAsrSessionImpl) {
                            currentStreamingSession = null
                        }
                    }
                }
            }
        }

        /**
         * Write a float PCM array as a WAV file (for temp file transcription).
         */
        private fun writeFloatArrayAsWav(data: FloatArray, wavFile: java.io.File) {
            val sampleRate = 16000
            val bitsPerSample = 16
            val numChannels = 1
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val dataSize = data.size * 2
            val fileSize = 36 + dataSize

            java.io.FileOutputStream(wavFile).use { out ->
                out.write("RIFF".toByteArray())
                out.write(intToByteArrayLE(fileSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToByteArrayLE(16))
                out.write(shortToByteArrayLE(1))
                out.write(shortToByteArrayLE(numChannels.toShort()))
                out.write(intToByteArrayLE(sampleRate))
                out.write(intToByteArrayLE(byteRate))
                out.write(shortToByteArrayLE(blockAlign.toShort()))
                out.write(shortToByteArrayLE(bitsPerSample.toShort()))
                out.write("data".toByteArray())
                out.write(intToByteArrayLE(dataSize))
                for (sample in data) {
                    val pcm = (sample * 32767f).toInt().coerceIn(-32768, 32767)
                    out.write(pcm and 0xFF)
                    out.write((pcm shr 8) and 0xFF)
                }
            }
        }

        private fun intToByteArrayLE(value: Int): ByteArray {
            return byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
        }

        private fun shortToByteArrayLE(value: Short): ByteArray {
            return byteArrayOf(
                (value.toInt() and 0xFF).toByte(),
                ((value.toInt() shr 8) and 0xFF).toByte()
            )
        }
    }

    companion object {
        private const val TAG = "AsrEngineImpl"
    }
}
