package com.speakin.app.ui.notedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.SegmentEntity
import com.speakin.app.data.repository.NoteRepository
import com.speakin.app.domain.asr.AsrEngine
import com.speakin.app.domain.audio.AudioPlayer
import com.speakin.app.domain.audio.AudioRecorder
import com.speakin.app.domain.llm.ModelManager
import com.speakin.app.domain.llm.ModelState
import com.speakin.app.domain.polish.PolishEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class NoteDetailUiState(
    val note: NoteEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val isPlaying: Boolean = false,
    val playingSegmentId: String? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val asrEngine: AsrEngine,
    private val polishEngine: PolishEngine,
    private val audioOutputDir: File,
    private val modelManager: ModelManager
) : ViewModel() {

    private val noteId: String = savedStateHandle.get<String>("noteId") ?: ""

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    private var currentAudioFile: File? = null

    val modelState: StateFlow<ModelState> = modelManager.modelState

    init {
        viewModelScope.launch {
            repository.getNoteByIdFlow(noteId).collect { note ->
                _uiState.value = _uiState.value.copy(
                    note = note,
                    isLoading = false
                )
            }
        }
        viewModelScope.launch {
            repository.getSegmentsByNoteId(noteId).collect { segments ->
                _uiState.value = _uiState.value.copy(segments = segments)
            }
        }
    }

    fun checkModel() {
        viewModelScope.launch {
            modelManager.checkAndPrepare()
        }
    }

    fun startRecording() {
        val fileName = "rec_${UUID.randomUUID().toString()}.m4a"
        val file = File(audioOutputDir, fileName)
        val started = audioRecorder.start(file)
        if (started) {
            currentAudioFile = file
            _uiState.value = _uiState.value.copy(isRecording = true)
        }
    }

    fun stopRecording() {
        val durationMs = audioRecorder.stop()
        val file = currentAudioFile
        if (file == null || durationMs <= 0) {
            _uiState.value = _uiState.value.copy(isRecording = false)
            return
        }

        _uiState.value = _uiState.value.copy(isRecording = false, isTranscribing = true)

        viewModelScope.launch {
            val segment = repository.addSegment(noteId, file, durationMs)

            withContext(Dispatchers.IO) {
                val transcribeSignal = kotlinx.coroutines.CompletableDeferred<String>()
                asrEngine.transcribe(file, object : AsrEngine.Callback {
                    override fun onResult(text: String) {
                        transcribeSignal.complete(text)
                    }
                    override fun onProgress(progress: Float) {}
                    override fun onError(error: String) {
                        transcribeSignal.complete("")
                    }
                })
                val rawText = transcribeSignal.await()

                repository.updateTranscription(segment.id, rawText)

                val polishSignal = kotlinx.coroutines.CompletableDeferred<String>()
                polishEngine.polish(rawText, object : PolishEngine.Callback {
                    override fun onResult(text: String) {
                        polishSignal.complete(text)
                    }
                    override fun onError(error: String) {
                        polishSignal.complete(rawText)
                    }
                })
                val polishedText = polishSignal.await()
                repository.updatePolishedText(segment.id, polishedText)
            }

            _uiState.value = _uiState.value.copy(isTranscribing = false)
        }
    }

    fun onPlaybackStarted(segmentId: String) {
        val segment = _uiState.value.segments.find { it.id == segmentId } ?: return
        val file = File(segment.audioFilePath)
        if (!file.exists()) return

        _uiState.value = _uiState.value.copy(playingSegmentId = segmentId, isPlaying = true)

        audioPlayer.play(file, object : AudioPlayer.PlaybackListener {
            override fun onCompletion() {
                _uiState.value = _uiState.value.copy(playingSegmentId = null, isPlaying = false)
            }

            override fun onError(error: String) {
                _uiState.value = _uiState.value.copy(playingSegmentId = null, isPlaying = false)
            }
        })
    }

    fun onPlaybackCompleted() {
        audioPlayer.stop()
        _uiState.value = _uiState.value.copy(playingSegmentId = null, isPlaying = false)
    }

    fun deleteSegment(segmentId: String) {
        viewModelScope.launch {
            if (segmentId == _uiState.value.playingSegmentId) {
                audioPlayer.stop()
                _uiState.value = _uiState.value.copy(playingSegmentId = null, isPlaying = false)
            }
            repository.deleteSegment(segmentId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            repository.updateNoteTitle(noteId, title)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
