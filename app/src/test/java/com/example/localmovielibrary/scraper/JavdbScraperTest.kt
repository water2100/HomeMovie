package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class JavdbScraperTest {
    @Test
    fun parsesMovieFieldsFromApiObject() {
        val movie = JavdbApiMovie(
            universalId = "SSIS-115",
            title = "Sample title",
            description = "Sample description",
            fullCoverUrl = "https://img.example/full.jpg",
            frontCoverUrl = "https://img.example/front.jpg",
            releaseDate = "2021-07-02",
            duration = "219",
            sourceUrl = "https://example.test/movie/ssis-115",
            maker = "Sample maker",
            label = "Sample label",
            series = "Sample series",
            actresses = listOf("Actor A", "Actor B"),
            directors = listOf("Director A"),
            genres = listOf("Genre A", "Genre B")
        )

        val info = parseJavdbMovieInfo("SSIS-115", movie)

        assertEquals("SSIS-115", info.number)
        assertEquals("Sample title", info.title)
        assertEquals("Sample description", info.plot)
        assertEquals("2021", info.year)
        assertEquals("2021-07-02", info.premiered)
        assertEquals("219", info.runtime)
        assertEquals("Sample maker", info.studio)
        assertEquals("Sample label", info.publisher)
        assertEquals("Sample series", info.series)
        assertEquals(listOf("Director A"), info.directors)
        assertEquals(listOf("Actor A", "Actor B"), info.actors)
        assertEquals(listOf("Genre A", "Genre B"), info.genres)
        assertEquals(info.genres, info.tags)
        assertEquals("", info.trailer)
        assertEquals("https://example.test/movie/ssis-115", info.website)
        assertEquals("javdb", info.source)
        assertEquals("https://img.example/full.jpg", info.thumbUrl)
        assertEquals("https://img.example/front.jpg", info.posterUrl)
        assertEquals(listOf("https://img.example/full.jpg"), info.thumbImageUrls)
        assertEquals(listOf("https://img.example/front.jpg"), info.posterImageUrls)
    }

    @Test
    fun normalizesQueryNumber() {
        assertEquals("SSIS-115", normalizeJavdbNumber("ssis00115"))
        assertEquals("NHDTC-190A", normalizeJavdbNumber("nhdtc-190a.mp4"))
    }
}
