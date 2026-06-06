package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_strm_records",
    indices = [
        Index(value = ["movieNumber"]),
        Index(value = ["libraryRootUri"]),
        Index(value = ["movieId"]),
        Index(value = ["movieNumber", "partLabel", "variant", "fileName"])
    ]
)
data class CloudStrmRecordEntity(
    @PrimaryKey
    val pickcode: String,
    val fileName: String,
    val movieNumber: String?,
    val variant: String?,
    val partLabel: String?,
    val strmUri: String,
    val libraryRootUri: String?,
    val movieId: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
