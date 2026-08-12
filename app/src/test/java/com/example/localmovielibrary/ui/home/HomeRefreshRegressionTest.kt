package com.example.localmovielibrary.ui.home

import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.ui.shared.artworkCacheRevision
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRefreshRegressionTest {
    @Test
    fun `new movies can be applied as one automatic refresh`() {
        val displayed = listOf(movie(id = 1, title = "Existing"))
        val latest = displayed + movie(id = 2, title = "New")

        val update = reduceHomeMovieListUpdate(displayed, latest)

        assertEquals(displayed, update.displayed)
        assertEquals(latest, update.pending)
        assertEquals(1, update.pendingCount)
        assertEquals(latest, update.pending.ifEmpty { update.displayed })
    }

    @Test
    fun `scrape metadata changes are staged instead of blanking visible cards`() {
        val displayed = listOf(movie(id = 1, title = "ABP-001", posterUri = "content://poster/old"))
        val latest = listOf(movie(id = 1, title = "Scraped title", posterUri = "content://poster/new"))

        val update = reduceHomeMovieListUpdate(displayed, latest)

        assertEquals(displayed, update.displayed)
        assertEquals(latest, update.pending)
        assertEquals(1, update.pendingCount)
    }

    @Test
    fun `task timestamp changes do not invalidate artwork cache`() {
        val before = movie(id = 1, title = "ABP-001", posterUri = "content://poster/1", updatedAt = 100)
        val after = before.copy(updatedAt = 200)

        assertEquals(before.artworkCacheRevision(), after.artworkCacheRevision())
    }

    @Test
    fun `initial database snapshot is displayed immediately`() {
        val latest = listOf(movie(id = 1, title = "Existing"))

        val update = reduceHomeMovieListUpdate(emptyList(), latest, isInitialLoad = true)

        assertEquals(latest, update.displayed)
        assertEquals(0, update.pendingCount)
    }

    @Test
    fun `movies added after an empty initial snapshot are automatically applicable`() {
        val latest = listOf(movie(id = 1, title = "New"))

        val update = reduceHomeMovieListUpdate(emptyList(), latest, isInitialLoad = false)

        assertEquals(emptyList<MovieEntity>(), update.displayed)
        assertEquals(latest, update.pending)
        assertEquals(1, update.pendingCount)
        assertEquals(latest, update.pending.ifEmpty { update.displayed })
    }

    @Test
    fun `unscraped movies are excluded from regular library tabs`() {
        val scraped = movie(id = 1, title = "Scraped")
        val unscraped = movie(id = 2, title = "Unscraped")

        val visible = excludeScrapeIssueMovies(
            movies = listOf(scraped, unscraped),
            scrapeIssueMovies = listOf(unscraped)
        )

        assertEquals(listOf(scraped), visible)
    }

    @Test
    fun `completed movie returns to regular library tabs`() {
        val completed = movie(id = 2, title = "Completed")

        val visible = excludeScrapeIssueMovies(
            movies = listOf(completed),
            scrapeIssueMovies = emptyList()
        )

        assertEquals(listOf(completed), visible)
    }

    private fun movie(
        id: Long,
        title: String,
        posterUri: String? = null,
        updatedAt: Long = 0
    ) = MovieEntity(
        id = id,
        libraryRootUri = "content://library",
        videoUri = "content://video/$id",
        videoName = "$title.strm",
        sortTitle = title,
        title = title,
        originalTitle = null,
        plot = null,
        outline = null,
        year = null,
        premiered = null,
        runtimeMinutes = null,
        mpaa = null,
        studios = emptyList(),
        series = null,
        directors = emptyList(),
        actors = emptyList(),
        genres = emptyList(),
        tags = emptyList(),
        rating = null,
        uniqueIds = emptyList(),
        posterUri = posterUri,
        fanartUri = null,
        thumbUri = null,
        nfoUri = null,
        scannedAtMillis = 1,
        updatedAt = updatedAt
    )
}
