package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {
    @Query("SELECT mediaKey, updatedAt FROM playback_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentListItems(limit: Int): Flow<List<PlaybackProgressListItem>>

    @Query("SELECT * FROM playback_progress WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun get(mediaKey: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE mediaKey = :mediaKey")
    suspend fun delete(mediaKey: String)
}
