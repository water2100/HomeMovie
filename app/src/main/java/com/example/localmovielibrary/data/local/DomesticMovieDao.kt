package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DomesticMovieDao {
    @Query("SELECT * FROM domestic_movies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DomesticMovieEntity>>

    @Query("SELECT * FROM domestic_movies WHERE folderCid = :folderCid LIMIT 1")
    suspend fun getByFolderCid(folderCid: Long): DomesticMovieEntity?

    @Query("SELECT folderCid FROM domestic_movies")
    suspend fun getAddedFolderCids(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DomesticMovieEntity)

    @Query("UPDATE domestic_movies SET imageUrl = :imageUrl, updatedAt = :updatedAt WHERE folderCid = :folderCid")
    suspend fun updateImageUrl(folderCid: Long, imageUrl: String, updatedAt: Long)
}
