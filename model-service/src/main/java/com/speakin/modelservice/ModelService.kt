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

        // DEBUG: Auto-test transcription if test file exists
        workerHandler?.postDelayed({
            testTranscribeFromFile()
        }, 3000)
    }

    /** Test: transcribe a known audio file to verify the Whisper pipeline. */
    private fun testTranscribeFromFile() {
        try {
            val testFile = File(filesDir, "test/helloHowareyoutoday.m4a")
            if (!testFile.exists()) {
                Log.i(TAG, "Test: no test file at ${testFile.absolutePath}")
                return
            }
            Log.i(TAG, "Test: found test file, decoding...")
            val pcm = readM4aAsFloat(testFile) ?: run {
                Log.e(TAG, "Test: M4A decode failed")
                return
            }
            Log.i(TAG, "Test: decoded ${pcm.size} samples, running ASR...")
            val modelDir = File(filesDir, "whisper")
            if (!asrEngine.load(modelDir)) {
                Log.e(TAG, "Test: model load failed")
                return
            }
            val result = asrEngine.transcribe(pcm)
            Log.i(TAG, "Test: transcription result = '$result'")
        } catch (e: Exception) {
            Log.e(TAG, "Test: error", e)
        }
    }

    /** Decode M4A/AAC file to 16kHz mono PCM float array using Android MediaExtractor. */
    private fun readM4aAsFloat(file: File): FloatArray? {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var sampleRate = 0
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    sampleRate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    Log.i(TAG, "Test: audio track=$i, mime=$mime, sr=$sampleRate")
                    break
                }
            }
            if (trackIndex < 0) { extractor.release(); return null }
            extractor.selectTrack(trackIndex)

            val decoder = android.media.MediaCodec.createDecoderByType(
                extractor.getTrackFormat(trackIndex).getString(android.media.MediaFormat.KEY_MIME)!!
            )
            decoder.configure(extractor.getTrackFormat(trackIndex), null, null, 0)
            decoder.start()

            val samples = mutableListOf<Float>()
            var done = false
            val info = android.media.MediaCodec.BufferInfo()

            while (!done) {
                val inIdx = decoder.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val shortData = ShortArray(info.size / 2)
                        outBuf.asShortBuffer().get(shortData)
                        outBuf.position(0)
                        for (s in shortData) samples.add(s.toFloat() / 32768f)
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> { /* other */ }
                }
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }

            decoder.stop(); decoder.release(); extractor.release()

            // Resample to 16kHz if needed
            val result = if (sampleRate != 16000) {
                resample(samples.toFloatArray(), sampleRate, 16000)
            } else samples.toFloatArray()

            Log.i(TAG, "Test: decoded ${result.size} samples @ 16kHz (${result.size / 16000f}s)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Test: M4A decode error", e)
            null
        }
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
