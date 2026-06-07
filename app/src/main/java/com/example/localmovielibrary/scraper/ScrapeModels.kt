package com.example.localmovielibrary.scraper

enum class ScrapeSource {
    Dmm,
    Dmm2,
    Official,
    Javbus,
    Missav
}

data class ScrapedMovieInfo(
    val number: String,
    val title: String,
    val originalTitle: String = title,
    val plot: String = "",
    val outline: String = plot,
    val year: String = "",
    val premiered: String = "",
    val runtime: String = "",
    val studio: String = "",
    val publisher: String = "",
    val series: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val actorImageUrls: Map<String, String> = emptyMap(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: String = "",
    val trailer: String = "",
    val website: String = "",
    val source: String = "",
    val thumbUrl: String = "",
    val posterUrl: String = ""
)

data class ScrapeRunResult(
    val scanned: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int
)
