package com.speakin.app.domain.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM(WAV) → M4A/AAC 编码器。
 *
 * 使用系统 MediaCodec + MediaMuxer，无需引入 FFmpeg。
 * 用于音频导出：内部存储为 WAV，导出为体积更小、兼容性好的 M4A。
 */
object AacEncoder {

    private const val TAG = "AacEncoder"
    private const val TIMEOUT_US = 10_000L
    private const val INPUT_CHUNK_BYTES = 8192

    /**
     * 将 PCM 数据编码为 AAC-LC 并封装为 M4A 文件。
     *
     * @throws IOException 编码或封装失败
     */
    fun encodeToM4a(data: WavData, outFile: File, bitRate: Int = 64_000) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        // PCM ShortArray -> 小端字节
        val pcmBytes = ByteArray(data.pcm.size * 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(data.pcm)

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, data.sampleRate, data.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_CHUNK_BYTES)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bytesPerFrame = 2 * data.channels
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // 喂入 PCM 数据
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        val remaining = pcmBytes.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val count = minOf(remaining, inputBuffer.capacity(), INPUT_CHUNK_BYTES)
                            inputBuffer.put(pcmBytes, inputOffset, count)
                            val presentationUs =
                                inputOffset.toLong() / bytesPerFrame * 1_000_000L / data.sampleRate
                            codec.queueInputBuffer(inputIndex, 0, count, presentationUs, 0)
                            inputOffset += count
                        }
                    }
                }

                // 取出编码结果写入 muxer
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // codec config 数据由 addTrack 的 format 携带，跳过
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            Log.i(TAG, "M4A exported: ${outFile.absolutePath} (${outFile.length()} bytes)")
        } catch (e: Exception) {
            outFile.delete()
            throw IOException("Failed to encode M4A: ${e.message}", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }
}
