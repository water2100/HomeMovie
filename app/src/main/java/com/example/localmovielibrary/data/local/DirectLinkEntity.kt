package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "direct_links")
data class DirectLinkEntity(
    @PrimaryKey val pickcode: String,
    val url: String,
    val expiresAt: Long,
    val updatedAt: Long
)
