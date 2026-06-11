package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_folder_batch_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["folderCid"]),
        Index(value = ["updatedAt"])
    ]
)
data class CloudFolderBatchTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderCid: Long,
    val folderName: String,
    val status: String = CloudFolderBatchTaskStatus.Pending.name,
    val currentPath: String? = null,
    val currentFileName: String? = null,
    val queuedVideos: Int = 0,
    val processedVideos: Int = 0,
    val addedVideos: Int = 0,
    val scrapeFailedVideos: Int = 0,
    val skippedVideos: Int = 0,
    val failedVideos: Int = 0,
    val failedFolders: Int = 0,
    val failureMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class CloudFolderBatchTaskStatus {
    Pending,
    Running,
    Paused,
    Completed,
    Failed
}
