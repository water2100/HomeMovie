package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.scraper.MovieNumberExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberRuleRefreshTest {
    @Test
    fun `movie number extraction only loads cached rules`() {
        var cachedRuleLoads = 0
        val number = extractNumberWithCachedRules(
            values = listOf("NACT-067.strm"),
            extractor = MovieNumberExtractor::extract,
            loadCachedRules = { cachedRuleLoads += 1 }
        )

        assertEquals("NACT-067", number)
        assertEquals(1, cachedRuleLoads)
    }

}
