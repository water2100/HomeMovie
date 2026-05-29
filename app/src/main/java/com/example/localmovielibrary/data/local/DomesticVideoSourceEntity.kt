package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "domestic_video_sources",
    indices = [
        Index(value = ["folderCid"]),
        Index(value = ["videoPickcode"], unique = true)
    ]
)
data class DomesticVideoSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderCid: Long,
    val videoName: String,
    val videoPickcode: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)
