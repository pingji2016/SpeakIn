package com.speakin.app.data.repository

import com.speakin.app.data.local.dao.ContentBlockDao
import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.dto.NoteStats
import com.speakin.app.data.local.entity.BlockType
import com.speakin.app.data.local.entity.ContentBlockEntity
import com.speakin.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val contentBlockDao: ContentBlockDao
) {

    fun getAllNotes(): Flow<List<NoteEntity>> = noteDao.getAllNotes()

    suspend fun getNoteById(noteId: String): NoteEntity? = noteDao.getNoteById(noteId)

    fun getNoteByIdFlow(noteId: String): Flow<NoteEntity?> = noteDao.getNoteByIdFlow(noteId)

    fun getBlocksByNoteId(noteId: String): Flow<List<ContentBlockEntity>> =
        contentBlockDao.getBlocksByNoteId(noteId)

    suspend fun createNote(title: String): NoteEntity {
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        noteDao.insertNote(note)
        return note
    }

    // ─── Voice Block ───────────────────────────────────────

    suspend fun addVoiceBlock(
        noteId: String,
        audioFile: File,
        durationMs: Long
    ): ContentBlockEntity {
        val sortOrder = contentBlockDao.getMaxSortOrder(noteId) + 1
        val now = System.currentTimeMillis()
        val block = ContentBlockEntity(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            blockType = BlockType.VOICE,
            audioFilePath = audioFile.absolutePath,
            durationMs = durationMs,
            createdAt = now,
            sortOrder = sortOrder
        )
        contentBlockDao.insertBlock(block)
        updateBlockCount(noteId)
        return block
    }

    suspend fun updateTranscription(blockId: String, transcription: String) {
        val block = contentBlockDao.getBlockById(blockId) ?: return
        contentBlockDao.updateBlock(block.copy(transcription = transcription))
    }

    suspend fun updatePolishedText(blockId: String, polishedText: String) {
        val block = contentBlockDao.getBlockById(blockId) ?: return
        contentBlockDao.updateBlock(block.copy(polishedText = polishedText))
    }

    // ─── Text Block ────────────────────────────────────────

    suspend fun addTextBlock(noteId: String, text: String = ""): ContentBlockEntity {
        val sortOrder = contentBlockDao.getMaxSortOrder(noteId) + 1
        val now = System.currentTimeMillis()
        val block = ContentBlockEntity(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            blockType = BlockType.TEXT,
            textContent = text,
            createdAt = now,
            sortOrder = sortOrder
        )
        contentBlockDao.insertBlock(block)
        updateBlockCount(noteId)
        return block
    }

    suspend fun updateTextBlock(blockId: String, text: String) {
        val block = contentBlockDao.getBlockById(blockId) ?: return
        contentBlockDao.updateBlock(block.copy(textContent = text))
    }

    // ─── Image Block ───────────────────────────────────────

    suspend fun addImageBlock(noteId: String, imageFile: File, caption: String = ""): ContentBlockEntity {
        val sortOrder = contentBlockDao.getMaxSortOrder(noteId) + 1
        val now = System.currentTimeMillis()
        val block = ContentBlockEntity(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            blockType = BlockType.IMAGE,
            textContent = caption,
            imageFilePath = imageFile.absolutePath,
            createdAt = now,
            sortOrder = sortOrder
        )
        contentBlockDao.insertBlock(block)
        updateBlockCount(noteId)
        return block
    }

    // ─── Block Operations ──────────────────────────────────

    suspend fun deleteBlock(blockId: String) {
        val block = contentBlockDao.getBlockById(blockId) ?: return
        // Clean up files
        block.audioFilePath?.let { File(it).delete() }
        block.imageFilePath?.let { File(it).delete() }
        contentBlockDao.deleteBlock(block)
        updateBlockCount(block.noteId)
    }

    suspend fun deleteNote(noteId: String) {
        // Clean up all block files
        val blocks = contentBlockDao.getBlocksByNoteId(noteId)
        // Note: Flow can't be collected here, use noteId for cascade delete at DB level
        noteDao.deleteNoteById(noteId)
    }

    suspend fun updateNoteTitle(noteId: String, title: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.updateNote(note.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun togglePinNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.setNotePinned(noteId, !note.isPinned)
    }

    suspend fun exportNoteAsText(noteId: String): String? {
        val note = noteDao.getNoteById(noteId) ?: return null
        val blocks = contentBlockDao.getBlocksByNoteIdOnce(noteId)
        return buildString {
            appendLine(note.title)
            appendLine("─".repeat(40))
            blocks.forEach { block ->
                appendLine()
                when (block.blockType) {
                    BlockType.TEXT -> {
                        if (block.textContent.isNotBlank()) {
                            appendLine(block.textContent)
                        }
                    }
                    BlockType.VOICE -> {
                        val text = block.polishedText?.takeIf { it.isNotBlank() }
                            ?: block.transcription?.takeIf { it.isNotBlank() }
                            ?: block.textContent
                        if (text.isNotBlank()) {
                            appendLine(text)
                        }
                    }
                    BlockType.IMAGE -> {
                        if (block.textContent.isNotBlank()) {
                            appendLine("[Image: ${block.textContent}]")
                        }
                    }
                }
            }
        }
    }

    fun searchNotes(query: String): Flow<List<NoteEntity>> = noteDao.searchNotes(query)

    suspend fun getNoteStats(noteId: String): NoteStats? {
        val note = noteDao.getNoteById(noteId) ?: return null
        val blocks = contentBlockDao.getBlocksByNoteIdOnce(noteId)
        return NoteStats(
            noteId = note.id,
            title = note.title,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            blockCount = blocks.size,
            textBlockCount = blocks.count { it.blockType == BlockType.TEXT },
            voiceBlockCount = blocks.count { it.blockType == BlockType.VOICE },
            imageBlockCount = blocks.count { it.blockType == BlockType.IMAGE },
            totalAudioDurationMs = blocks.filter { it.blockType == BlockType.VOICE }
                .sumOf { it.durationMs ?: 0L },
            totalTextLength = blocks.sumOf { block ->
                (block.textContent?.length ?: 0) +
                    (block.transcription?.length ?: 0) +
                    (block.polishedText?.length ?: 0)
            }
        )
    }

    private suspend fun updateBlockCount(noteId: String) {
        val count = contentBlockDao.getBlockCount(noteId)
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.updateNote(
            note.copy(
                blockCount = count,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
