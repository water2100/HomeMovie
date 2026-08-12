package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberPrefixRewriteRuleTest {
    private val rule = NumberPrefixRewriteRule(
        prefix = "DSVR",
        numericPrefix = "3",
        sources = setOf(ScrapeSource.Javbus, ScrapeSource.TheJavDB)
    )

    @Test
    fun rewritesOutputNumberAndNeverDuplicatesTheNumericPrefix() {
        assertEquals("3DSVR-1944", rewriteNumberPrefix("DSVR-1944", listOf(rule)))
        assertEquals("3DSVR-1944", rewriteNumberPrefix("3DSVR-1944", listOf(rule)))
    }

    @Test
    fun onlyRewritesTheSelectedScrapeSources() {
        assertEquals("DSVR-1944", rewriteNumberPrefix("DSVR-1944", listOf(rule), ScrapeSource.Dmm2))
        assertEquals("3DSVR-1944", rewriteNumberPrefix("DSVR-1944", listOf(rule), ScrapeSource.Javbus))
    }
}
