package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DomesticVideoSourceDao {
    @Query("SELECT * FROM domestic_video_sources ORDER BY folderCid ASC, sortOrder ASC, videoName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<DomesticVideoSourceEntity>>

    @Query("SELECT * FROM domestic_video_sources WHERE folderCid = :folderCid ORDER BY sortOrder ASC, videoName COLLATE NOCASE ASC")
    suspend fun getByFolderCid(folderCid: Long): List<DomesticVideoSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<DomesticVideoSourceEntity>)
}
