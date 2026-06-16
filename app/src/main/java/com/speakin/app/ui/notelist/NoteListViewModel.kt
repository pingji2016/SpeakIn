package com.speakin.app.ui.notelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteListUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true
)

sealed class NoteListEvent {
    data class NavigateToDetail(val noteId: String) : NoteListEvent()
}

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NoteListEvent>()
    val events: SharedFlow<NoteListEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.getAllNotes().collect { notes ->
                _uiState.value = NoteListUiState(
                    notes = notes,
                    isLoading = false
                )
            }
        }
    }

    fun createNote() {
        viewModelScope.launch {
            val note = repository.createNote("Untitled Note")
            _events.emit(NoteListEvent.NavigateToDetail(note.id))
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }
}
