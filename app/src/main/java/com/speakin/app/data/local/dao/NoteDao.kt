package com.speakin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.speakin.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun getNoteByIdFlow(noteId: String): Flow<NoteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: String)

    @Query("DELETE FROM notes WHERE id IN (:noteIds)")
    suspend fun deleteNotesByIds(noteIds: List<String>)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :noteId")
    suspend fun setNotePinned(noteId: String, isPinned: Boolean)

    @Query("UPDATE notes SET contentJson = :contentJson, blockCount = :blockCount, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun updateContent(noteId: String, contentJson: String?, blockCount: Int, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun getNoteCount(): Int

    @Query("""
        SELECT DISTINCT n.* FROM notes n
        LEFT JOIN content_blocks cb ON n.id = cb.noteId
        WHERE n.title LIKE '%' || :query || '%'
           OR n.contentJson LIKE '%' || :query || '%'
           OR cb.textContent LIKE '%' || :query || '%'
           OR cb.transcription LIKE '%' || :query || '%'
           OR cb.polishedText LIKE '%' || :query || '%'
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNotes(query: String): Flow<List<NoteEntity>>
}
