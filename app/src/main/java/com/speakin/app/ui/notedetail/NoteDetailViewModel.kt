package com.speakin.app.ui.notedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.BlockType
import com.speakin.app.data.local.entity.ContentBlockEntity
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.repository.NoteRepository
import com.speakin.app.di.AudioDir
import com.speakin.app.di.ImageDir
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
    val blocks: List<ContentBlockEntity> = emptyList(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val playingBlockId: String? = null,
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
    @AudioDir private val audioOutputDir: File,
    @ImageDir private val imageOutputDir: File,
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
            repository.getBlocksByNoteId(noteId).collect { blocks ->
                _uiState.value = _uiState.value.copy(blocks = blocks)
            }
        }
    }

    fun checkModel() {
        viewModelScope.launch {
            modelManager.checkAndPrepare()
        }
    }

    // ─── Voice Recording ───────────────────────────────────

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
            val block = repository.addVoiceBlock(noteId, file, durationMs)

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

                repository.updateTranscription(block.id, rawText)

                if (rawText.isNotBlank()) {
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
                    repository.updatePolishedText(block.id, polishedText)
                }
            }

            _uiState.value = _uiState.value.copy(isTranscribing = false)
        }
    }

    fun onPlaybackStarted(blockId: String) {
        val block = _uiState.value.blocks.find { it.id == blockId } ?: return
        val path = block.audioFilePath ?: return
        val file = File(path)
        if (!file.exists()) return

        _uiState.value = _uiState.value.copy(playingBlockId = blockId)

        audioPlayer.play(file, object : AudioPlayer.PlaybackListener {
            override fun onCompletion() {
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
            override fun onError(error: String) {
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
        })
    }

    fun onPlaybackStopped() {
        audioPlayer.stop()
        _uiState.value = _uiState.value.copy(playingBlockId = null)
    }

    // ─── Text Block ────────────────────────────────────────

    fun addTextBlock() {
        viewModelScope.launch {
            repository.addTextBlock(noteId)
        }
    }

    fun updateTextBlock(blockId: String, text: String) {
        viewModelScope.launch {
            repository.updateTextBlock(blockId, text)
        }
    }

    // ─── Image Block ───────────────────────────────────────

    fun addImageBlock(imageFile: File) {
        viewModelScope.launch {
            // Copy image to app storage
            val destDir = imageOutputDir
            destDir.mkdirs()
            val destFile = File(destDir, "img_${UUID.randomUUID()}.jpg")
            imageFile.copyTo(destFile, overwrite = true)
            repository.addImageBlock(noteId, destFile)
        }
    }

    // ─── Block Operations ──────────────────────────────────

    fun deleteBlock(blockId: String) {
        viewModelScope.launch {
            if (blockId == _uiState.value.playingBlockId) {
                audioPlayer.stop()
                _uiState.value = _uiState.value.copy(playingBlockId = null)
            }
            repository.deleteBlock(blockId)
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
