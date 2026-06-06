package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_progress",
    indices = [
        Index(value = ["updatedAt"])
    ]
)
data class PlaybackProgressEntity(
    @PrimaryKey val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

data class PlaybackProgressListItem(
    val mediaKey: String,
    val updatedAt: Long
)
