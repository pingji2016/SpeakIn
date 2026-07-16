package com.speakin.app.domain.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 基于 AudioTrack 的选区精确预览播放器。
 *
 * 直接播放内存中的 PCM 片段，可精确停在任意毫秒终点
 * （MediaPlayer 无法做到），用于音频编辑器的裁剪预览。
 */
class RangePlayer {

    companion object {
        private const val TAG = "RangePlayer"
        private const val PROGRESS_INTERVAL_MS = 33L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null

    /**
     * 播放 [startMs, endMs) 范围的 PCM 数据。
     * 若正在播放会先停止上一次播放。
     *
     * @param onProgressMs 播放进度回调（相对整段音频的毫秒位置），主线程外调用
     * @param onComplete 播放到终点时回调
     */
    fun play(
        data: WavData,
        startMs: Long,
        endMs: Long,
        onProgressMs: (Long) -> Unit,
        onComplete: () -> Unit
    ) {
        stop()

        val slice = WavFile.sliceByMs(data, startMs, endMs)
        if (slice.pcm.isEmpty()) {
            onComplete()
            return
        }

        playJob = scope.launch {
            var track: AudioTrack? = null
            try {
                val channelMask = if (slice.channels >= 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }
                val minBuffer = AudioTrack.getMinBufferSize(
                    slice.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(slice.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer.coerceAtLeast(4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
                track.play()

                // 后台写入 PCM（MODE_STREAM 的 write 会阻塞直到缓冲区可用）
                val writer = launch(Dispatchers.IO) {
                    var offset = 0
                    val chunk = 4096
                    while (isActive && offset < slice.pcm.size) {
                        val count = minOf(chunk, slice.pcm.size - offset)
                        val written = track.write(slice.pcm, offset, count)
                        if (written <= 0) break
                        offset += written
                    }
                }

                // 轮询播放头位置，回报进度并检测结束
                val totalFrames = slice.pcm.size / slice.channels
                while (isActive) {
                    val headFrames = track.playbackHeadPosition
                    val posMs = startMs + headFrames.toLong() * 1000L / slice.sampleRate
                    onProgressMs(posMs.coerceAtMost(endMs))
                    if (headFrames >= totalFrames) break
                    delay(PROGRESS_INTERVAL_MS)
                }
                writer.cancel()

                if (isActive) {
                    withContext(Dispatchers.Main) { onComplete() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                if (isActive) {
                    withContext(Dispatchers.Main) { onComplete() }
                }
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                track?.release()
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    /** 停止当前播放。 */
    fun stop() {
        playJob?.cancel()
        playJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
    }

    /** 释放资源，之后不可再使用。 */
    fun release() {
        stop()
        scope.cancel()
    }
}
