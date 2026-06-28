package com.speakin.app.ui.notelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speakin.app.data.local.dto.NoteStats
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isGridView: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedNoteIds: Set<String> = emptySet()
)

sealed class NoteListEvent {
    data class NavigateToDetail(val noteId: String) : NoteListEvent()
    data class ShowExportShareSheet(val title: String, val text: String) : NoteListEvent()
    data class ShowNoteDetails(val stats: NoteStats) : NoteListEvent()
}

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NoteListEvent>()
    val events: SharedFlow<NoteListEvent> = _events.asSharedFlow()

    private var notesJob: Job? = null

    init {
        collectNotes()
    }

    private fun collectNotes() {
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            val query = _uiState.value.searchQuery
            val flow = if (query.isNotBlank()) {
                repository.searchNotes(query)
            } else {
                repository.getAllNotes()
            }
            flow.collect { notes ->
                _uiState.value = _uiState.value.copy(
                    notes = notes,
                    isLoading = false
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        collectNotes()
    }

    fun toggleSearch() {
        val newState = !_uiState.value.isSearchActive
        _uiState.value = _uiState.value.copy(
            isSearchActive = newState,
            searchQuery = if (!newState) "" else _uiState.value.searchQuery
        )
        if (!newState) {
            collectNotes() // reset to all notes
        }
    }

    fun toggleViewMode() {
        _uiState.value = _uiState.value.copy(
            isGridView = !_uiState.value.isGridView
        )
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

    // ─── Batch Selection ──────────────────────────────────

    fun enterSelectionMode(noteId: String) {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedNoteIds = setOf(noteId)
        )
    }

    fun toggleSelection(noteId: String) {
        val current = _uiState.value.selectedNoteIds
        _uiState.value = _uiState.value.copy(
            selectedNoteIds = if (noteId in current) current - noteId else current + noteId,
            isSelectionMode = if (current.size == 1 && noteId in current) false else true
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedNoteIds = emptySet()
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedNoteIds = _uiState.value.notes.map { it.id }.toSet()
        )
    }

    fun deleteSelectedNotes() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedNoteIds.toList()
            repository.deleteNotes(ids)
            exitSelectionMode()
        }
    }

    fun renameNote(noteId: String, newTitle: String) {
        viewModelScope.launch {
            repository.updateNoteTitle(noteId, newTitle)
        }
    }

    fun togglePin(noteId: String) {
        viewModelScope.launch {
            repository.togglePinNote(noteId)
        }
    }

    fun exportNote(noteId: String) {
        viewModelScope.launch {
            val note = repository.getNoteById(noteId) ?: return@launch
            val text = repository.exportNoteAsText(noteId) ?: return@launch
            _events.emit(NoteListEvent.ShowExportShareSheet(note.title, text))
        }
    }

    fun showNoteDetails(noteId: String) {
        viewModelScope.launch {
            val stats = repository.getNoteStats(noteId) ?: return@launch
            _events.emit(NoteListEvent.ShowNoteDetails(stats))
        }
    }
}
