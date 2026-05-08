package com.speakin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.speakin.app.data.local.entity.SegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY sortOrder ASC")
    fun getSegmentsByNoteId(noteId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE id = :segmentId")
    suspend fun getSegmentById(segmentId: String): SegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: SegmentEntity)

    @Update
    suspend fun updateSegment(segment: SegmentEntity)

    @Delete
    suspend fun deleteSegment(segment: SegmentEntity)

    @Query("DELETE FROM segments WHERE id = :segmentId")
    suspend fun deleteSegmentById(segmentId: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM segments WHERE noteId = :noteId")
    suspend fun getMaxSortOrder(noteId: String): Int

    @Query("SELECT COUNT(*) FROM segments WHERE noteId = :noteId")
    suspend fun getSegmentCount(noteId: String): Int
}
