package com.speakin.app.domain.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 解码后的 WAV 音频数据。
 *
 * @param sampleRate 采样率（Hz）
 * @param channels 声道数
 * @param bitsPerSample 位深（目前仅支持 16-bit）
 * @param pcm 交织的 PCM 采样数据
 */
data class WavData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcm: ShortArray
) {
    /** 时长（毫秒） */
    val durationMs: Long
        get() = if (sampleRate <= 0 || channels <= 0) 0L
        else pcm.size.toLong() / channels * 1000L / sampleRate

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WavData) return false
        return sampleRate == other.sampleRate &&
                channels == other.channels &&
                bitsPerSample == other.bitsPerSample &&
                pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + pcm.contentHashCode()
        return result
    }
}

/**
 * WAV 文件读写工具。
 *
 * 与 AudioRecorder 写出的格式对应（16kHz 16-bit 单声道 PCM），
 * 但读取时按 RIFF chunk 遍历，不假设 data 块固定在 44 字节偏移处。
 */
object WavFile {

    /**
     * 读取 WAV 文件为 PCM 数据。
     *
     * @throws IOException 文件不是有效的 16-bit PCM WAV
     */
    fun read(file: File): WavData {
        val bytes = file.readBytes()
        if (bytes.size < 12 ||
            String(bytes, 0, 4) != "RIFF" ||
            String(bytes, 8, 4) != "WAVE"
        ) {
            throw IOException("Not a valid WAV file: ${file.name}")
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        // 按 chunk 遍历，找到 fmt 和 data 块
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = readIntLE(bytes, pos + 4)
            val body = pos + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) throw IOException("Truncated fmt chunk")
                    val audioFormat = readShortLE(bytes, body).toInt()
                    if (audioFormat != 1) throw IOException("Unsupported WAV format: $audioFormat (PCM only)")
                    channels = readShortLE(bytes, body + 2).toInt()
                    sampleRate = readIntLE(bytes, body + 4)
                    bitsPerSample = readShortLE(bytes, body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataSize = chunkSize.coerceAtMost(bytes.size - body)
                }
            }
            // chunk 按 2 字节对齐
            pos = body + chunkSize + (chunkSize and 1)
        }

        if (sampleRate <= 0 || channels <= 0 || dataOffset < 0) {
            throw IOException("Missing fmt/data chunk: ${file.name}")
        }
        if (bitsPerSample != 16) {
            throw IOException("Unsupported bits per sample: $bitsPerSample (16-bit only)")
        }

        // ByteArray -> ShortArray（小端）
        val sampleCount = dataSize / 2
        val pcm = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            pcm[i] = readShortLE(bytes, dataOffset + i * 2)
        }

        return WavData(sampleRate, channels, bitsPerSample, pcm)
    }

    /**
     * 将 PCM 数据写为标准 WAV 文件（44 字节 header，与 AudioRecorder.pcmToWav 一致）。
     */
    fun write(file: File, data: WavData) {
        val bytesPerSample = data.bitsPerSample / 8
        val dataSize = data.pcm.size * bytesPerSample
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(fileSize))
            out.write("WAVE".toByteArray())

            // fmt chunk
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))                          // PCM format
            out.write(shortToByteArray(data.channels.toShort()))
            out.write(intToByteArray(data.sampleRate))
            out.write(intToByteArray(data.sampleRate * data.channels * bytesPerSample)) // byte rate
            out.write(shortToByteArray((data.channels * bytesPerSample).toShort()))     // block align
            out.write(shortToByteArray(data.bitsPerSample.toShort()))

            // data chunk
            out.write("data".toByteArray())
            out.write(intToByteArray(dataSize))
            val bytes = ByteArray(dataSize)
            for (i in data.pcm.indices) {
                val s = data.pcm[i].toInt()
                bytes[i * 2] = (s and 0xFF).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }

    /**
     * 按毫秒范围裁剪 PCM 数据（自动 clamp 到有效范围）。
     */
    fun sliceByMs(data: WavData, startMs: Long, endMs: Long): WavData {
        val totalFrames = data.pcm.size / data.channels
        val startFrame = (startMs * data.sampleRate / 1000L).coerceIn(0L, totalFrames.toLong()).toInt()
        val endFrame = (endMs * data.sampleRate / 1000L).coerceIn(startFrame.toLong(), totalFrames.toLong()).toInt()
        val slice = data.pcm.copyOfRange(startFrame * data.channels, endFrame * data.channels)
        return data.copy(pcm = slice)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
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
