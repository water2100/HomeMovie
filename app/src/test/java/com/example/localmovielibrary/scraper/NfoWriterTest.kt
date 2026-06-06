package com.example.localmovielibrary.scraper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfoWriterTest {
    @Test
    fun build_removesZeroTrimmedNumberPrefixFromTitle() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "SNIS-00253",
                title = "SNIS-253 New Face NO.1 STYLE Aoi AV debut",
                originalTitle = "SNIS-253 New Face NO.1 STYLE Aoi AV debut"
            )
        )

        assertTrue(nfo.contains("<title>[SNIS-00253]New Face NO.1 STYLE Aoi AV debut</title>"))
        assertFalse(nfo.contains("[SNIS-00253]SNIS-253"))
    }

    @Test
    fun build_removesCompactNumberPrefixFromTitle() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "SNIS-00253",
                title = "snis00253 New Face NO.1 STYLE Aoi AV debut",
                originalTitle = "snis00253 New Face NO.1 STYLE Aoi AV debut"
            )
        )

        assertTrue(nfo.contains("<title>[SNIS-00253]New Face NO.1 STYLE Aoi AV debut</title>"))
        assertFalse(nfo.contains("[SNIS-00253]snis00253"))
    }
}
