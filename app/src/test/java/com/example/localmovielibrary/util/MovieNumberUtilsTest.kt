package com.example.localmovielibrary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieNumberUtilsTest {
    @Test
    fun parsesStandardAndAvoidsSitePrefixAsVariant() {
        val identity = extractMovieSourceIdentity("4k2.me@fns-128.mp4")

        assertEquals("FNS-128", identity?.number)
        assertEquals(null, identity?.partLabel)
        assertEquals(MovieVariant.Standard, identity?.variant)
        assertEquals("FNS-128", identity?.sourceKey)
    }

    @Test
    fun extractsLastMovieNumberAfterSitePrefix() {
        assertEquals("FNS-150", extractMovieSourceIdentity("hhd800.com@FNS-150.MP4")?.number)
        assertEquals("FNS-172", extractMovieSourceIdentity("hhd800.com@FNS-172.MP4")?.number)
    }

    @Test
    fun prefersSeparatedNumberBeforeBracketedCodeAndTitleText() {
        val identity = extractMovieSourceIdentity("IDBD-317(idbd00317)[IDEAPOCKET]IPSUPERGIRLSBEST100人16時間.strm")

        assertEquals("IDBD-317", identity?.number)
    }

    @Test
    fun parsesFourKVersion() {
        val identity = extractMovieSourceIdentity("4k2.me@fns-128-4k.mp4")

        assertEquals("FNS-128", identity?.number)
        assertEquals(null, identity?.partLabel)
        assertEquals(MovieVariant.FourK, identity?.variant)
        assertEquals("FNS-128-4K", identity?.sourceKey)
    }

    @Test
    fun parsesLetterSegments() {
        val identity = extractMovieSourceIdentity("MDVR-312-B.mp4")

        assertEquals("MDVR-312", identity?.number)
        assertEquals("B", identity?.partLabel)
        assertEquals(MovieVariant.Standard, identity?.variant)
        assertEquals("MDVR-312-B", identity?.sourceKey)
    }

    @Test
    fun parsesPartAndEightKVersion() {
        val identity = extractMovieSourceIdentity("ebvr00104.part3_8K.mp4")

        assertEquals("EBVR-00104", identity?.number)
        assertEquals("P3", identity?.partLabel)
        assertEquals(MovieVariant.EightK, identity?.variant)
        assertEquals("EBVR-00104-P3-8K", identity?.sourceKey)
    }

    @Test
    fun parsesUnderscoreNumberSegments() {
        val first = extractMovieSourceIdentity("www.98T.la@vrprd00156_1_8k.mp4")
        val second = extractMovieSourceIdentity("www.98T.la@vrprd00156_2_8k.mp4")
        val noPart = extractMovieSourceIdentity("www.98T.la@vrprd00156_8k.mp4")

        assertEquals("VRPRD-00156", first?.number)
        assertEquals("P1", first?.partLabel)
        assertEquals(MovieVariant.EightK, first?.variant)
        assertEquals("P2", second?.partLabel)
        assertEquals(MovieVariant.EightK, second?.variant)
        assertEquals(null, noPart?.partLabel)
        assertEquals(MovieVariant.EightK, noPart?.variant)
    }

    @Test
    fun parsesSixtyFpsAndCombinedVersion() {
        assertEquals(MovieVariant.SixtyFps, extractMovieSourceIdentity("HMN-645_60FPS.mp4")?.variant)
        assertEquals(MovieVariant.FourK60Fps, extractMovieSourceIdentity("HMN-645_4K60FPS.mp4")?.variant)
        assertEquals(MovieVariant.FourK, extractMovieSourceIdentity("START-155_4Ks.mp4")?.variant)
    }

    @Test
    fun parsesMgstageNumberWithNumericPrefix() {
        val identity = extractMovieSourceIdentity("300MIUM-111.mp4")

        assertEquals("300MIUM-111", identity?.number)
        assertEquals(MovieVariant.Standard, identity?.variant)
        assertEquals("300MIUM-111", identity?.sourceKey)
    }

    @Test
    fun ignoresConfiguredTrailingNoiseSuffix() {
        val identity = extractMovieSourceIdentity("meyd00772hhb.strm")

        assertEquals("MEYD-772", identity?.number)
        assertEquals(MovieVariant.Standard, identity?.variant)
        assertEquals("MEYD-772", identity?.sourceKey)
    }

    @Test
    fun keepsRealTrailingLetterSuffixAsPartOfNumber() {
        val identity = extractMovieSourceIdentity("DANDY-852A.strm")

        assertEquals("DANDY-852A", identity?.number)
        assertEquals(null, identity?.partLabel)
    }
}
