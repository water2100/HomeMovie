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

    @Query("SELECT pickcode FROM cloud_strm_records WHERE pickcode IN (:pickcodes)")
    suspend fun getExistingPickcodes(pickcodes: List<String>): List<String>

    @Query("SELECT * FROM cloud_strm_records WHERE pickcode IN (:pickcodes)")
    suspend fun getExistingRecords(pickcodes: List<String>): List<CloudStrmRecordEntity>

    @Query("SELECT * FROM cloud_strm_records WHERE movieNumber = :movieNumber ORDER BY partLabel COLLATE NOCASE")
    suspend fun getByMovieNumber(movieNumber: String): List<CloudStrmRecordEntity>

    @Query(
        """
        SELECT * FROM cloud_strm_records
        WHERE movieNumber = :movieNumber
            AND partLabel IS NULL
            AND (variant IS NULL OR variant = '')
            AND pickcode != :excludedPickcode
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getStandardSameNumberCandidate(movieNumber: String, excludedPickcode: String): CloudStrmRecordEntity?

    @Query("SELECT * FROM cloud_strm_records WHERE movieId = :movieId ORDER BY partLabel COLLATE NOCASE, variant COLLATE NOCASE, fileName COLLATE NOCASE")
    suspend fun getByMovieId(movieId: Long): List<CloudStrmRecordEntity>

    @Query(
        """
        SELECT * FROM cloud_strm_records
        WHERE movieId = :movieId
            OR (
                movieNumber = :movieNumber
                AND (
                    libraryRootUri = :libraryRootUri
                    OR strmUri = :strmUri
                )
            )
        ORDER BY partLabel COLLATE NOCASE, variant COLLATE NOCASE, fileName COLLATE NOCASE
        """
    )
    suspend fun getPlaybackRecords(
        movieId: Long,
        movieNumber: String,
        libraryRootUri: String,
        strmUri: String
    ): List<CloudStrmRecordEntity>

    @Query("SELECT * FROM cloud_strm_records WHERE movieId IN (:movieIds)")
    suspend fun getByMovieIds(movieIds: List<Long>): List<CloudStrmRecordEntity>

    @Query("SELECT * FROM cloud_strm_records WHERE libraryRootUri = :libraryRootUri")
    suspend fun getByLibraryRoot(libraryRootUri: String): List<CloudStrmRecordEntity>

    @Query("DELETE FROM cloud_strm_records WHERE pickcode = :pickcode")
    suspend fun deleteByPickcode(pickcode: String)

    @Query("DELETE FROM cloud_strm_records WHERE pickcode IN (:pickcodes)")
    suspend fun deleteByPickcodes(pickcodes: List<String>)

    @Query("DELETE FROM cloud_strm_records WHERE movieId = :movieId")
    suspend fun deleteByMovieId(movieId: Long)

    @Query("DELETE FROM cloud_strm_records WHERE movieId IN (:movieIds)")
    suspend fun deleteByMovieIds(movieIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CloudStrmRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<CloudStrmRecordEntity>)

    @Query("DELETE FROM cloud_strm_records")
    suspend fun clear()
}
