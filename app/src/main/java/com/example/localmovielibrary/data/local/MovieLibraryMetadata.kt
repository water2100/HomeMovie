package com.example.localmovielibrary.data.local

data class MovieLibraryMetadata(
    val id: Long,
    val studios: List<String>,
    val series: String?,
    val directors: List<String>,
    val actors: List<String>,
    val genres: List<String>,
    val tags: List<String>
)
