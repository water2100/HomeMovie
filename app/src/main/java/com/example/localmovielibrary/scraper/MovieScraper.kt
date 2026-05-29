package com.example.localmovielibrary.scraper

interface MovieScraper {
    val source: ScrapeSource

    suspend fun scrape(number: String): ScrapedMovieInfo
}

class MovieScraperRegistry(
    scrapers: List<MovieScraper>
) {
    private val scrapersBySource = scrapers.associateBy { it.source }

    suspend fun scrape(source: ScrapeSource, number: String): ScrapedMovieInfo {
        val scraper = scrapersBySource[source] ?: error("Unsupported scrape source: $source")
        return scraper.scrape(number)
    }
}
