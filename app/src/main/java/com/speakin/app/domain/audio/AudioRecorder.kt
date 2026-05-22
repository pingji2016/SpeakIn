package com.speakin.app.domain.audio

import android.content.Context
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTime: Long = 0L
    private var _isRecording: Boolean = false

    fun start(outputFile: File): Boolean {
        return try {
            mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            currentFile = outputFile
            startTime = System.currentTimeMillis()
            _isRecording = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stop(): Long {
        val duration = if (startTime > 0) System.currentTimeMillis() - startTime else 0L
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
        _isRecording = false
        return duration
    }

    fun isRecording(): Boolean = _isRecording
}
