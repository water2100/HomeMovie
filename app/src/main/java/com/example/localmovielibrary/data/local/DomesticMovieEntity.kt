package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "domestic_movies",
    indices = [
        Index(value = ["folderCid"], unique = true),
        Index(value = ["videoPickcode"], unique = true)
    ]
)
data class DomesticMovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderCid: Long,
    val folderName: String,
    val videoName: String,
    val videoPickcode: String,
    val imageName: String?,
    val imagePickcode: String?,
    val imageUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
)
