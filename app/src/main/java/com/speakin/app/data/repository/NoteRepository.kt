package com.speakin.app.data.repository

import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.dao.SegmentDao
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.SegmentEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val segmentDao: SegmentDao
) {

    fun getAllNotes(): Flow<List<NoteEntity>> = noteDao.getAllNotes()

    suspend fun getNoteById(noteId: String): NoteEntity? = noteDao.getNoteById(noteId)

    fun getNoteByIdFlow(noteId: String): Flow<NoteEntity?> = noteDao.getNoteByIdFlow(noteId)

    fun getSegmentsByNoteId(noteId: String): Flow<List<SegmentEntity>> =
        segmentDao.getSegmentsByNoteId(noteId)

    suspend fun createNote(title: String): NoteEntity {
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        noteDao.insertNote(note)
        return note
    }

    suspend fun addSegment(
        noteId: String,
        audioFile: File,
        durationMs: Long
    ): SegmentEntity {
        val sortOrder = segmentDao.getMaxSortOrder(noteId) + 1
        val now = System.currentTimeMillis()
        val segment = SegmentEntity(
            id = java.util.UUID.randomUUID().toString(),
            noteId = noteId,
            audioFilePath = audioFile.absolutePath,
            durationMs = durationMs,
            createdAt = now,
            sortOrder = sortOrder
        )
        segmentDao.insertSegment(segment)

        val segmentCount = segmentDao.getSegmentCount(noteId)
        noteDao.updateNote(
            noteDao.getNoteById(noteId)?.copy(
                segmentCount = segmentCount,
                updatedAt = System.currentTimeMillis()
            ) ?: return segment
        )
        return segment
    }

    suspend fun updateTranscription(segmentId: String, rawText: String) {
        val segment = segmentDao.getSegmentById(segmentId) ?: return
        segmentDao.updateSegment(segment.copy(rawText = rawText))
    }

    suspend fun updatePolishedText(segmentId: String, polishedText: String) {
        val segment = segmentDao.getSegmentById(segmentId) ?: return
        segmentDao.updateSegment(segment.copy(polishedText = polishedText))
    }

    suspend fun deleteSegment(segmentId: String) {
        val segment = segmentDao.getSegmentById(segmentId) ?: return
        segmentDao.deleteSegment(segment)
        val segmentCount = segmentDao.getSegmentCount(segment.noteId)
        noteDao.updateNote(
            noteDao.getNoteById(segment.noteId)?.copy(
                segmentCount = segmentCount,
                updatedAt = System.currentTimeMillis()
            ) ?: return
        )
    }

    suspend fun deleteNote(noteId: String) {
        noteDao.deleteNoteById(noteId)
    }

    suspend fun updateNoteTitle(noteId: String, title: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.updateNote(note.copy(title = title, updatedAt = System.currentTimeMillis()))
    }
}
