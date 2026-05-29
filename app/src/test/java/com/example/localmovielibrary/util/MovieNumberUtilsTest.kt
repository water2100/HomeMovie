package com.example.localmovielibrary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieNumberUtilsTest {
    @Test
    fun extractsLastMovieNumberAfterSitePrefix() {
        assertEquals("FNS-150", extractMovieNumberInfo("hhd800.com@FNS-150.MP4")?.number)
        assertEquals("FNS-172", extractMovieNumberInfo("hhd800.com@FNS-172.MP4")?.number)
        assertEquals("FNS-128", extractMovieNumberInfo("4k2.me@fns-128.mp4")?.number)
        assertEquals("FNS-128", extractMovieNumberInfo("4k2.me@fns-128-4k.mp4")?.number)
    }

    @Test
    fun keepsSegmentPartLabelFromLastMovieNumber() {
        val info = extractMovieNumberInfo("hhd800.com@MDVR-312-B.mp4")
        assertEquals("MDVR-312", info?.number)
        assertEquals("B", info?.partLabel)
    }

    @Test
    fun extractsNumberedPartLabels() {
        val part4 = extractMovieNumberInfo("ebvr00104.part4_8K.mp4")
        assertEquals("EBVR-00104", part4?.number)
        assertEquals("P4", part4?.partLabel)

        val part3 = extractMovieNumberInfo("ebvr00104.part3_8K.mp4")
        assertEquals("EBVR-00104", part3?.number)
        assertEquals("P3", part3?.partLabel)
    }

    @Test
    fun extractsNormalizedNumberedPartLabels() {
        val info = extractMovieNumberInfo("EBVR-00104-P4-8K.strm")
        assertEquals("EBVR-00104", info?.number)
        assertEquals("P4", info?.partLabel)
    }

    @Test
    fun extractsDirectNumericPartBeforeQualityMarker() {
        val part1 = extractMovieNumberInfo("www.98T.la@vrprd00156_1_8k.mp4")
        assertEquals("VRPRD-00156", part1?.number)
        assertEquals("P1", part1?.partLabel)

        val part2 = extractMovieNumberInfo("www.98T.la@vrprd00156_2_8k.mp4")
        assertEquals("VRPRD-00156", part2?.number)
        assertEquals("P2", part2?.partLabel)
    }

    @Test
    fun doesNotTreatQualityOnlySuffixAsPart() {
        val info = extractMovieNumberInfo("www.98T.la@vrprd00156_8k.mp4")
        assertEquals("VRPRD-00156", info?.number)
        assertEquals(null, info?.partLabel)
    }
}
