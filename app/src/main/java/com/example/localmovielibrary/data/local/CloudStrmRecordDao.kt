package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloudStrmRecordDao {
    @Query("SELECT * FROM cloud_strm_records WHERE pickcode = :pickcode LIMIT 1")
    suspend fun get(pickcode: String): CloudStrmRecordEntity?

    @Query("SELECT pickcode FROM cloud_strm_records")
    suspend fun getAllPickcodes(): List<String>

    @Query("SELECT * FROM cloud_strm_records")
    suspend fun getAll(): List<CloudStrmRecordEntity>

    @Query("SELECT * FROM cloud_strm_records WHERE pickcode IN (:pickcodes)")
    suspend fun getByPickcodes(pickcodes: List<String>): List<CloudStrmRecordEntity>

    @Query("SELECT * FROM cloud_strm_records WHERE movieNumber = :movieNumber ORDER BY partLabel COLLATE NOCASE")
    suspend fun getByMovieNumber(movieNumber: String): List<CloudStrmRecordEntity>

    @Query("DELETE FROM cloud_strm_records WHERE pickcode = :pickcode")
    suspend fun deleteByPickcode(pickcode: String)

    @Query("DELETE FROM cloud_strm_records WHERE pickcode IN (:pickcodes)")
    suspend fun deleteByPickcodes(pickcodes: List<String>)

    @Query("DELETE FROM cloud_strm_records WHERE movieId = :movieId")
    suspend fun deleteByMovieId(movieId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CloudStrmRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<CloudStrmRecordEntity>)

    @Query("DELETE FROM cloud_strm_records")
    suspend fun clear()
}
