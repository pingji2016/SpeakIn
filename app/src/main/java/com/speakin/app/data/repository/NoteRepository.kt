package com.speakin.app.data.repository

import com.speakin.app.data.local.dao.ContentBlockDao
import com.speakin.app.data.local.dao.NoteDao
import com.speakin.app.data.local.dto.NoteStats
import com.speakin.app.data.local.entity.BlockType
import com.speakin.app.data.local.entity.ContentBlockEntity
import com.speakin.app.data.local.entity.NoteEntity
import com.speakin.app.data.local.entity.DocNode
import com.speakin.app.data.local.entity.RichSegment
import com.speakin.app.data.local.entity.flattenSegments
import com.speakin.app.util.FormatUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val contentBlockDao: ContentBlockDao
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

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
            updatedAt = now,
            contentJson = json.encodeToString(emptyList<DocNode>())  // empty rich doc
        )
        noteDao.insertNote(note)
        return note
    }

    // ─── Rich Content (v4) ─────────────────────────────────

    suspend fun saveContent(noteId: String, blocks: List<DocNode>) {
        val contentJson = json.encodeToString(blocks)
        val blockCount = blocks.flattenSegments().size  // count nested segments for list display
        noteDao.updateContent(noteId, contentJson, blockCount, System.currentTimeMillis())
    }

    fun getContent(noteId: String): Flow<List<DocNode>?> {
        return noteDao.getNoteByIdFlow(noteId).map { note ->
            note?.contentJson?.let { parseContent(it) }
        }
    }

    suspend fun getContentOnce(noteId: String): List<DocNode>? {
        return noteDao.getNoteById(noteId)?.contentJson?.let { parseContent(it) }
    }

    fun parseSegments(jsonStr: String?): List<DocNode>? {
        return jsonStr?.let { parseContent(it) }
    }

    /**
     * 更新指定音频段的文件路径与时长（音频编辑器裁剪保存后调用）。
     * 仅支持顶层 Segment 中的 Audio；列内 Audio 暂不支持。
     */
    suspend fun updateAudioSegment(
        noteId: String,
        segmentIndex: Int,
        newPath: String,
        newDurationMs: Long
    ) {
        val blocks = getContentOnce(noteId)?.toMutableList() ?: return
        val node = blocks.getOrNull(segmentIndex) as? DocNode.Segment ?: return
        val audio = node.content as? RichSegment.Audio ?: return
        blocks[segmentIndex] = DocNode.Segment(
            audio.copy(audioPath = newPath, durationMs = newDurationMs)
        )
        saveContent(noteId, blocks)
    }

    // ─── Content JSON parsing (two-phase with backwards compat) ──

    /**
     * Parse [jsonStr] into [DocNode] list.
     * Phase 1: try new DocNode format.
     * Phase 2: fall back to flat [RichSegment] list and wrap each in DocNode.Segment.
     */
    private fun parseContent(jsonStr: String): List<DocNode> {
        // Phase 1: DocNode format (v5+)
        try {
            return json.decodeFromString<List<DocNode>>(jsonStr)
        } catch (_: Exception) {
            // Phase 2: flat RichSegment format (v4) — wrap for backwards compat
            try {
                val segments: List<RichSegment> = json.decodeFromString(jsonStr)
                return segments.map { DocNode.Segment(it) }
            } catch (_: Exception) {
                return emptyList()
            }
        }
    }

    /**
     * 将旧版 content_blocks 表数据转换为 RichSegment 列表并保存到 contentJson。
     * 仅在 note.contentJson 为 null 时调用，迁移后不再重复。
     */
    suspend fun migrateLegacyNoteIfNeeded(noteId: String): List<DocNode>? {
        val note = noteDao.getNoteById(noteId) ?: return null
        // Already migrated — return existing content
        note.contentJson?.let { return parseContent(it) }

        val blocks = contentBlockDao.getBlocksByNoteIdOnce(noteId)
        if (blocks.isEmpty()) return null

        val nodes = blocks.map { block ->
            val segment = when (block.blockType) {
                BlockType.TEXT -> RichSegment.Text(
                    text = block.textContent.orEmpty()
                )
                BlockType.VOICE -> RichSegment.Audio(
                    audioPath = block.audioFilePath.orEmpty(),
                    durationMs = block.durationMs ?: 0L,
                    transcription = block.transcription?.takeIf { it.isNotBlank() },
                    polishedText = block.polishedText?.takeIf { it.isNotBlank() }
                )
                BlockType.IMAGE -> RichSegment.Image(
                    imagePath = block.imageFilePath.orEmpty(),
                    altText = block.textContent.orEmpty()
                )
            }
            DocNode.Segment(segment)
        }

        // Persist the migration
        saveContent(noteId, nodes)
        return nodes
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
        // Clean up rich-content media files (nested inside columns as well)
        val note = noteDao.getNoteById(noteId)
        note?.contentJson?.let { parseContent(it) }?.flattenSegments()?.forEach { seg ->
            when (seg) {
                is RichSegment.Image -> File(seg.imagePath).delete()
                is RichSegment.Audio -> File(seg.audioPath).delete()
                else -> {}
            }
        }
        // Clean up legacy block files
        val blocks = contentBlockDao.getBlocksByNoteIdOnce(noteId)
        blocks.forEach { block ->
            block.audioFilePath?.let { File(it).delete() }
            block.imageFilePath?.let { File(it).delete() }
        }
        noteDao.deleteNoteById(noteId)
    }

    suspend fun deleteNotes(noteIds: List<String>) {
        // Clean up media files for all notes first
        for (noteId in noteIds) {
            val note = noteDao.getNoteById(noteId)
            note?.contentJson?.let { parseContent(it) }?.flattenSegments()?.forEach { seg ->
                when (seg) {
                    is RichSegment.Image -> File(seg.imagePath).delete()
                    is RichSegment.Audio -> File(seg.audioPath).delete()
                    else -> {}
                }
            }
            // Clean up legacy block files
            val blocks = contentBlockDao.getBlocksByNoteIdOnce(noteId)
            blocks.forEach { block ->
                block.audioFilePath?.let { File(it).delete() }
                block.imageFilePath?.let { File(it).delete() }
            }
        }
        // Batch delete from database
        noteDao.deleteNotesByIds(noteIds)
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

        // Prefer rich content (v4+/v5+)
        val docNodes = note.contentJson?.let { parseContent(it) }
        if (docNodes != null) {
            val allSegments = docNodes.flattenSegments()
            return buildString {
                appendLine(note.title)
                appendLine("─".repeat(40))
                allSegments.forEach { seg ->
                    appendLine()
                    when (seg) {
                        is RichSegment.Text -> {
                            if (seg.text.isNotBlank()) appendLine(seg.text)
                        }
                        is RichSegment.Audio -> {
                            val text = seg.polishedText?.takeIf { it.isNotBlank() }
                                ?: seg.transcription?.takeIf { it.isNotBlank() }
                            if (text != null) appendLine(text)
                            else appendLine("[Audio: ${FormatUtils.formatDuration(seg.durationMs)}]")
                        }
                        is RichSegment.Image -> {
                            appendLine("[Image${if (seg.altText.isNotBlank()) ": ${seg.altText}" else ""}]")
                        }
                    }
                }
            }
        }

        // Fallback: legacy blocks
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

        // Rich content path (v4+/v5+)
        val docNodes = note.contentJson?.let { parseContent(it) }
        if (docNodes != null) {
            val allSegments = docNodes.flattenSegments()
            return NoteStats(
                noteId = note.id,
                title = note.title,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                blockCount = docNodes.size,
                textBlockCount = allSegments.count { it is RichSegment.Text },
                voiceBlockCount = allSegments.count { it is RichSegment.Audio },
                imageBlockCount = allSegments.count { it is RichSegment.Image },
                totalAudioDurationMs = allSegments
                    .filterIsInstance<RichSegment.Audio>()
                    .fold(0L) { acc, audio -> acc + audio.durationMs },
                totalTextLength = allSegments.fold(0) { acc, seg ->
                    acc + when (seg) {
                        is RichSegment.Text -> seg.text.length
                        is RichSegment.Audio -> (seg.transcription?.length ?: 0) +
                            (seg.polishedText?.length ?: 0)
                        is RichSegment.Image -> seg.altText.length
                    }
                },
                usesRichContent = true
            )
        }

        // Legacy blocks fallback
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
