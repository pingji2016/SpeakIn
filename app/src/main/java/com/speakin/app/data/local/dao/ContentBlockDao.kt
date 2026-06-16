package com.speakin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.speakin.app.data.local.entity.ContentBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentBlockDao {

    @Query("SELECT * FROM content_blocks WHERE noteId = :noteId ORDER BY sortOrder ASC")
    fun getBlocksByNoteId(noteId: String): Flow<List<ContentBlockEntity>>

    @Query("SELECT * FROM content_blocks WHERE id = :blockId")
    suspend fun getBlockById(blockId: String): ContentBlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: ContentBlockEntity)

    @Update
    suspend fun updateBlock(block: ContentBlockEntity)

    @Delete
    suspend fun deleteBlock(block: ContentBlockEntity)

    @Query("DELETE FROM content_blocks WHERE id = :blockId")
    suspend fun deleteBlockById(blockId: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM content_blocks WHERE noteId = :noteId")
    suspend fun getMaxSortOrder(noteId: String): Int

    @Query("SELECT COUNT(*) FROM content_blocks WHERE noteId = :noteId")
    suspend fun getBlockCount(noteId: String): Int
}
