package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DirectLinkDao {
    @Query("SELECT * FROM direct_links WHERE pickcode = :pickcode LIMIT 1")
    suspend fun get(pickcode: String): DirectLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DirectLinkEntity)

    @Query("DELETE FROM direct_links WHERE pickcode = :pickcode")
    suspend fun delete(pickcode: String)
}
