package com.speakin.app.domain.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor(
    private val context: Context
) {

    private var mediaPlayer: MediaPlayer? = null
    private var _isPlaying: Boolean = false
    private var playbackListener: PlaybackListener? = null

    interface PlaybackListener {
        fun onCompletion()
        fun onError(error: String)
    }

    fun play(file: File, listener: PlaybackListener? = null) {
        release()
        if (!file.exists()) {
            listener?.onError("File not found: ${file.absolutePath}")
            return
        }
        playbackListener = listener
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _isPlaying = false
                    listener?.onCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    _isPlaying = false
                    listener?.onError("MediaPlayer error: $what / $extra")
                    true
                }
                prepare()
                start()
            }
            _isPlaying = true
        } catch (e: Exception) {
            _isPlaying = false
            listener?.onError(e.message ?: "Unknown error")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        _isPlaying = false
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {
        }
        _isPlaying = false
    }

    fun resume() {
        try {
            mediaPlayer?.start()
        } catch (_: Exception) {
        }
        _isPlaying = true
    }

    fun release() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _isPlaying = false
    }

    fun isPlaying(): Boolean = _isPlaying
}
