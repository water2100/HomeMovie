package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudVideoTaskDao {
    @Query(
        """
        SELECT * FROM cloud_video_tasks
        ORDER BY
            CASE status
                WHEN 'Running' THEN 0
                WHEN 'Pending' THEN 1
                WHEN 'Paused' THEN 2
                WHEN 'Failed' THEN 3
                ELSE 4
            END,
            createdAt ASC
        """
    )
    fun observeAll(): Flow<List<CloudVideoTaskEntity>>

    @Query("SELECT COUNT(*) FROM cloud_video_tasks WHERE status != 'Completed'")
    fun observeUnfinishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cloud_video_tasks WHERE status != 'Completed'")
    suspend fun countUnfinished(): Int

    @Query(
        """
        SELECT DISTINCT record.movieId
        FROM cloud_video_tasks AS task
        INNER JOIN cloud_strm_records AS record ON record.pickcode = task.pickcode
        WHERE task.status != 'Completed' AND record.movieId IS NOT NULL
        """
    )
    suspend fun unfinishedOwnedMovieIds(): List<Long>

    @Query("SELECT * FROM cloud_video_tasks WHERE pickcode = :pickcode LIMIT 1")
    suspend fun get(pickcode: String): CloudVideoTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: CloudVideoTaskEntity)

    @Query("SELECT * FROM cloud_video_tasks WHERE status = 'Pending' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextPending(): CloudVideoTaskEntity?

    @Query(
        """
        UPDATE cloud_video_tasks
        SET status = 'Running', failureReason = NULL, updatedAt = :updatedAt
        WHERE pickcode = :pickcode AND status = 'Pending'
        """
    )
    suspend fun markRunningIfPending(pickcode: String, updatedAt: Long): Int

    @Transaction
    suspend fun claimNext(): CloudVideoTaskEntity? {
        val task = nextPending() ?: return null
        return if (markRunningIfPending(task.pickcode, System.currentTimeMillis()) == 1) {
            task.copy(status = CloudVideoTaskStatus.Running.name, failureReason = null)
        } else {
            null
        }
    }

    @Query(
        """
        UPDATE cloud_video_tasks
        SET status = :status, failureReason = :failureReason, updatedAt = :updatedAt
        WHERE pickcode = :pickcode
        """
    )
    suspend fun updateStatus(
        pickcode: String,
        status: String,
        failureReason: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE cloud_video_tasks
        SET status = 'Pending', failureReason = NULL, updatedAt = :updatedAt
        WHERE status IN ('Running', 'Paused', 'Failed')
        """
    )
    suspend fun prepareForStart(updatedAt: Long): Int

    @Query("DELETE FROM cloud_video_tasks WHERE status = 'Completed'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM cloud_video_tasks WHERE status != 'Completed'")
    suspend fun deleteUnfinished(): Int
}
