package com.example.localmovielibrary.scanner

data class NfoMetadata(
    val title: String? = null,
    val originalTitle: String? = null,
    val plot: String? = null,
    val outline: String? = null,
    val year: Int? = null,
    val premiered: String? = null,
    val runtimeMinutes: Int? = null,
    val mpaa: String? = null,
    val studios: List<String> = emptyList(),
    val series: String? = null,
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    val uniqueIds: List<String> = emptyList()
)
