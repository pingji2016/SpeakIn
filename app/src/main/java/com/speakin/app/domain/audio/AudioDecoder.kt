package com.speakin.app.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes compressed audio (M4A, MP3, OGG, etc.) to 16kHz 16-bit mono PCM WAV
 * using Android's MediaExtractor + MediaCodec.
 *
 * Used when importing external audio files that are not already in WAV format.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1

    /**
     * Decode an audio file by URI (any format supported by Android MediaCodec) to WAV.
     */
    fun decodeToWav(inputUri: Uri, outputFile: File, context: Context): Boolean {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, inputUri, null)
            decodeLoop(extractor, outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio from URI", e)
            false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Decode an audio file by path (M4A, MP3, etc.) to 16kHz 16-bit mono WAV.
     */
    fun decodeToWav(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists()) return false
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)
            decodeLoop(extractor, outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio file", e)
            false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Get audio duration in milliseconds using MediaMetadataRetriever.
     * Works for most audio formats supported by Android.
     */
    fun getDurationMs(file: File): Long {
        if (!file.exists()) return 0L
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, outputFile: File): Boolean {
        var codec: MediaCodec? = null
        return try {
            // Find the first audio track
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (trackIndex < 0) {
                Log.w(TAG, "No audio track found")
                return false
            }

            extractor.selectTrack(trackIndex)

            val mime = inputFormat!!.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // Resampler for converting to 16kHz mono if needed
            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = if (inputSampleRate != TARGET_SAMPLE_RATE || inputChannels != TARGET_CHANNELS) {
                AudioResampler(inputSampleRate, inputChannels, TARGET_SAMPLE_RATE, TARGET_CHANNELS)
            } else {
                null
            }

            // Decode loop
            val bufferInfo = MediaCodec.BufferInfo()
            var sawEos = false
            val pcmList = mutableListOf<Short>()

            while (!sawEos) {
                // Feed input
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(
                            inIndex, 0, sampleSize,
                            extractor.sampleTime,
                            extractor.sampleFlags
                        )
                        extractor.advance()
                    }
                }

                // Drain output
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            val samples = extractShorts(outBuf, bufferInfo)
                            if (resampler != null) {
                                pcmList.addAll(resampler.process(samples).toList())
                            } else {
                                pcmList.addAll(samples.toList())
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEos = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Output format changed — can be ignored for PCM
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }
                }
            }

            // Flush resampler tail
            resampler?.let {
                val tail = it.flush()
                if (tail.isNotEmpty()) pcmList.addAll(tail.toList())
            }

            if (pcmList.isEmpty()) {
                Log.w(TAG, "Decoded audio is empty")
                return false
            }

            // Write as 16kHz 16-bit mono WAV
            val wavData = WavData(
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitsPerSample = 16,
                pcm = pcmList.toShortArray()
            )
            WavFile.write(outputFile, wavData)
            Log.i(TAG, "Decoded ${wavData.durationMs}ms audio to ${outputFile.name}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio", e)
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun extractShorts(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ShortArray {
        val sizeShorts = info.size / 2
        val result = ShortArray(sizeShorts)
        val origPos = buffer.position()
        buffer.position(info.offset)
        for (i in 0 until sizeShorts) {
            result[i] = buffer.short
        }
        buffer.position(origPos)
        return result
    }
}

/**
 * Simple linear-interpolation resampler for PCM audio.
 * Converts from [srcSampleRate] / [srcChannels] to [dstSampleRate] / [dstChannels].
 */
internal class AudioResampler(
    private val srcSampleRate: Int,
    private val srcChannels: Int,
    private val dstSampleRate: Int,
    private val dstChannels: Int
) {
    private val ratio: Double = srcSampleRate.toDouble() / dstSampleRate
    private var fraction: Double = 0.0
    private var prevSample: Short = 0

    fun process(input: ShortArray): ShortArray {
        val output = mutableListOf<Short>()

        var i = 0
        while (i < input.size) {
            // Mix down to mono: average all channels
            var mono = 0
            for (ch in 0 until srcChannels) {
                if (i + ch < input.size) {
                    mono += input[i + ch].toInt()
                }
            }
            mono /= srcChannels
            val sample = mono.toShort()

            // Resample using linear interpolation
            while (fraction < 1.0 && i < input.size) {
                val interp = (prevSample + ((sample - prevSample) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())).toShort()
                output.add(interp)
                fraction += ratio
            }
            fraction -= 1.0
            prevSample = sample
            i += srcChannels
        }

        return output.toShortArray()
    }

    fun flush(): ShortArray {
        return if (fraction > 0.0 && fraction < 1.0) {
            shortArrayOf(prevSample)
        } else {
            ShortArray(0)
        }
    }
}
