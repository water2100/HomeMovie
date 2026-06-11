package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudFolderBatchTaskDao {
    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        ORDER BY
            CASE status
                WHEN 'Running' THEN 0
                WHEN 'Pending' THEN 1
                WHEN 'Paused' THEN 2
                WHEN 'Failed' THEN 3
                ELSE 4
            END,
            updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<CloudFolderBatchTaskEntity>>

    @Query("SELECT COUNT(*) FROM cloud_folder_batch_tasks WHERE status IN (:statuses)")
    fun observeTaskCount(statuses: List<String>): Flow<Int>

    @Query("SELECT * FROM cloud_folder_batch_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): CloudFolderBatchTaskEntity?

    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        WHERE folderCid = :folderCid AND status != 'Completed'
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getUnfinishedByFolderCid(folderCid: Long): CloudFolderBatchTaskEntity?

    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        WHERE status IN (:statuses)
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    suspend fun nextRunnable(statuses: List<String>): CloudFolderBatchTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: CloudFolderBatchTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: CloudFolderBatchTaskEntity): Long

    @Query("DELETE FROM cloud_folder_batch_tasks WHERE status = 'Completed'")
    suspend fun deleteCompleted(): Int
}
