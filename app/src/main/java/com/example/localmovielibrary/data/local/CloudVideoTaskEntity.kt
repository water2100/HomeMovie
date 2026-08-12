package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_video_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class CloudVideoTaskEntity(
    @PrimaryKey
    val pickcode: String,
    val fileName: String,
    val cid: Long? = null,
    val fid: Long? = null,
    val size: Long? = null,
    val addedAt: Long? = null,
    val modifiedAt: Long? = null,
    val forceDistinct: Boolean = false,
    val status: String = CloudVideoTaskStatus.Pending.name,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class CloudVideoTaskStatus {
    Pending,
    Running,
    Paused,
    Completed,
    Failed
}
