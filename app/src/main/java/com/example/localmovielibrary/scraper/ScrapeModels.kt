package com.example.localmovielibrary.scraper

import java.util.Locale

enum class ScrapeSource {
    Priority,
    Dmm,
    Dmm2,
    Official,
    Mgstage,
    Javbus,
    Missav
}

enum class MissavScrapeLanguage(
    val id: String,
    val pathSegment: String
) {
    Japanese("ja", "ja"),
    Chinese("cn", "cn");

    fun movieUrl(number: String): String =
        "https://missav.ai/$pathSegment/${number.lowercase(Locale.ROOT)}"

    val referer: String
        get() = "https://missav.ai/$pathSegment"

    val acceptLanguage: String
        get() = when (this) {
            Japanese -> "ja,en;q=0.8,zh-CN;q=0.7,zh;q=0.6"
            Chinese -> "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7"
        }

    companion object {
        val Default = Japanese

        fun fromId(id: String?): MissavScrapeLanguage {
            val normalized = id?.trim().orEmpty()
            return entries.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: Default
        }
    }
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
    val posterUrl: String = "",
    val thumbImageUrls: List<String> = emptyList(),
    val posterImageUrls: List<String> = emptyList()
)

data class ScrapeRunResult(
    val scanned: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int
)
