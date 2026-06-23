package com.speakin.app.domain.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频录制器。
 *
 * 使用 AudioRecord 录制 16kHz 16-bit 单声道 PCM，
 * 保存为标准 WAV 格式，可直接喂给 ASR 引擎和 MediaPlayer。
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    /**
     * 音频块监听器 — 用于流式识别场景。
     * 回调在录制线程中执行，实现方必须快速返回，避免阻塞录音。
     */
    interface AudioChunkListener {
        /**
         * 当有新音频数据可用时回调。
         * @param chunk PCM 数据，16kHz 16-bit 单声道 ShortArray
         */
        fun onAudioChunk(chunk: ShortArray)
    }

    private var audioRecord: AudioRecord? = null
    private var currentFile: File? = null
    private var startTime: Long = 0L
    private var _isRecording: Boolean = false
    private var recordingThread: Thread? = null
    private var totalSamplesWritten: Long = 0L
    private var chunkListener: AudioChunkListener? = null

    fun isRecording(): Boolean = _isRecording

    /**
     * 设置音频块监听器（传 null 取消监听）。
     */
    fun setChunkListener(listener: AudioChunkListener?) {
        this.chunkListener = listener
    }

    /**
     * 开始录音。
     *
     * @param outputFile 输出 WAV 文件路径
     * @return 是否成功启动
     */
    fun start(outputFile: File): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_MULTIPLIER

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $bufferSize")
                return false
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            currentFile = outputFile
            totalSamplesWritten = 0L
            startTime = System.currentTimeMillis()
            _isRecording = true

            audioRecord?.startRecording()

            // 在后台线程写入 PCM 数据
            recordingThread = Thread {
                writePcmData(outputFile, bufferSize)
            }.apply { start() }

            Log.i(TAG, "Recording started: ${outputFile.absolutePath}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            false
        }
    }

    /**
     * 后台线程：从 AudioRecord 读取 PCM 数据并写入文件。
     */
    private fun writePcmData(outputFile: File, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2) // 16-bit 每个 sample 2 字节
        val pcmFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.pcm")
        var fileOutputStream: FileOutputStream? = null

        try {
            fileOutputStream = FileOutputStream(pcmFile)

            while (_isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    // ShortArray → ByteArray
                    val byteBuffer = ByteArray(bytesRead * 2)
                    for (i in 0 until bytesRead) {
                        val s = buffer[i]
                        byteBuffer[i * 2] = (s.toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }
                    fileOutputStream.write(byteBuffer)
                    totalSamplesWritten += bytesRead.toLong()

                    // 通知流式监听器（传递有效采样数据的副本）
                    chunkListener?.let { listener ->
                        try {
                            val validChunk = if (bytesRead == buffer.size) buffer
                            else buffer.copyOf(bytesRead)
                            listener.onAudioChunk(validChunk)
                        } catch (e: Exception) {
                            Log.w(TAG, "Chunk listener error", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
        } finally {
            try {
                fileOutputStream?.flush()
                fileOutputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PCM file", e)
            }

            // 录音结束后，将 PCM 封装为 WAV
            pcmToWav(pcmFile, outputFile)
            pcmFile.delete()
        }
    }

    /**
     * 停止录音。
     *
     * @return 录音时长（毫秒）
     */
    fun stop(): Long {
        _isRecording = false
        val duration = if (startTime > 0) System.currentTimeMillis() - startTime else 0L

        try {
            recordingThread?.join(3000) // 等待写入线程结束
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for recording thread")
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        recordingThread = null
        startTime = 0L
        chunkListener = null

        Log.i(TAG, "Recording stopped. Duration: ${duration}ms, samples: $totalSamplesWritten")
        return duration
    }

    /**
     * 将原始 PCM 数据封装为 WAV 文件。
     */
    private fun pcmToWav(pcmFile: File, wavFile: File) {
        try {
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                Log.w(TAG, "No PCM data to convert")
                // 创建空 WAV
                writeEmptyWav(wavFile)
                return
            }

            val pcmData = pcmFile.readBytes()
            val dataSize = pcmData.size
            val fileSize = 36 + dataSize // WAV header 44 bytes
            val bitsPerSample = 16
            val bytesPerSample = bitsPerSample / 8
            val numChannels = 1

            FileOutputStream(wavFile).use { out ->
                // RIFF header
                out.write("RIFF".toByteArray())
                out.write(intToByteArray(fileSize))
                out.write("WAVE".toByteArray())

                // fmt chunk
                out.write("fmt ".toByteArray())
                out.write(intToByteArray(16))           // chunk size
                out.write(shortToByteArray(1.toShort()))           // PCM format
                out.write(shortToByteArray(numChannels.toShort())) // 1 channel
                out.write(intToByteArray(SAMPLE_RATE))
                out.write(intToByteArray(SAMPLE_RATE * numChannels * bytesPerSample)) // byte rate
                out.write(shortToByteArray((numChannels * bytesPerSample).toShort())) // block align
                out.write(shortToByteArray(bitsPerSample.toShort()))

                // data chunk
                out.write("data".toByteArray())
                out.write(intToByteArray(dataSize))
                out.write(pcmData)
            }

            Log.i(TAG, "WAV saved: ${wavFile.absolutePath} (${fileSize} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert PCM to WAV", e)
        }
    }

    private fun writeEmptyWav(wavFile: File) {
        // 写入最小有效 WAV header（空白录音）
        FileOutputStream(wavFile).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(36))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1.toShort()))           // PCM format
            out.write(shortToByteArray(1.toShort()))           // 1 channel
            out.write(intToByteArray(SAMPLE_RATE))
            out.write(intToByteArray(SAMPLE_RATE * 2))
            out.write(shortToByteArray(2.toShort()))           // block align
            out.write(shortToByteArray(16.toShort()))          // bitsPerSample
            out.write("data".toByteArray())
            out.write(intToByteArray(0))
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}
