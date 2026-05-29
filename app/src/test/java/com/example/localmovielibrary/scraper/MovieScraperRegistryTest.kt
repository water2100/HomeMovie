package com.example.localmovielibrary.scraper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MovieScraperRegistryTest {
    @Test
    fun scrapeRoutesToMatchingSource() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                FakeMovieScraper(ScrapeSource.Dmm, "dmm-title"),
                FakeMovieScraper(ScrapeSource.Missav, "missav-title")
            )
        )

        val info = registry.scrape(ScrapeSource.Missav, "ABC-123")

        assertEquals("missav-title", info.title)
        assertEquals("ABC-123", info.number)
    }

    @Test
    fun scrapeFailsWhenSourceIsNotRegistered() {
        val registry = MovieScraperRegistry(
            listOf(FakeMovieScraper(ScrapeSource.Dmm, "dmm-title"))
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { registry.scrape(ScrapeSource.Official, "ABC-123") }
        }
    }

    private class FakeMovieScraper(
        override val source: ScrapeSource,
        private val title: String
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            return ScrapedMovieInfo(
                number = number,
                title = title
            )
        }
    }
}
