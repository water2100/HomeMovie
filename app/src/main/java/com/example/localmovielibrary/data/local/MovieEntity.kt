package com.example.localmovielibrary.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["videoUri"], unique = true),
        Index(value = ["libraryRootUri"]),
        Index(value = ["sortTitle"]),
        Index(value = ["scannedAtMillis"]),
        Index(value = ["updatedAt"]),
        Index(value = ["year"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isWatched"]),
        Index(value = ["scrapeTaskStatus"])
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val libraryRootUri: String,
    val videoUri: String,
    val videoName: String,
    val sortTitle: String,
    val title: String,
    val originalTitle: String?,
    val plot: String?,
    val outline: String?,
    val year: Int?,
    val premiered: String?,
    val runtimeMinutes: Int?,
    val mpaa: String?,
    val studios: List<String>,
    val series: String?,
    val directors: List<String>,
    val actors: List<String>,
    val genres: List<String>,
    val tags: List<String>,
    val rating: Double?,
    val uniqueIds: List<String>,
    val posterUri: String?,
    val fanartUri: String?,
    val thumbUri: String?,
    val nfoUri: String?,
    val scannedAtMillis: Long,
    val isFavorite: Boolean = false,
    val isWatched: Boolean = false,
    val updatedAt: Long = 0,
    val scrapeFailureReason: String? = null,
    val scrapeTaskStatus: String = ScrapeTaskStatus.None.name
)

enum class ScrapeTaskStatus {
    None,
    Pending,
    Running,
    Completed,
    Failed;

    companion object {
        val unfinishedNames: List<String> = listOf(Pending.name, Running.name, Failed.name)
    }
}
