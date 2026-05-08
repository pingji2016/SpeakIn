package com.speakin.app.ui.notedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.SegmentEntity
import com.speakin.app.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class NoteDetailUiState(
    val note: NoteEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val playingSegmentId: String? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository
) : ViewModel() {

    private val noteId: String = savedStateHandle.get<String>("noteId") ?: ""

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

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

    fun startRecording() {
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    fun stopRecording() {
        _uiState.value = _uiState.value.copy(isRecording = false, isTranscribing = true)
    }

    fun onTranscriptionComplete(segmentId: String, rawText: String) {
        viewModelScope.launch {
            repository.updateTranscription(segmentId, rawText)
            _uiState.value = _uiState.value.copy(isTranscribing = false)
        }
    }

    fun onPlaybackStarted(segmentId: String) {
        _uiState.value = _uiState.value.copy(playingSegmentId = segmentId)
    }

    fun onPlaybackCompleted() {
        _uiState.value = _uiState.value.copy(playingSegmentId = null)
    }

    fun deleteSegment(segmentId: String) {
        viewModelScope.launch {
            repository.deleteSegment(segmentId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            repository.updateNoteTitle(noteId, title)
        }
    }
}
