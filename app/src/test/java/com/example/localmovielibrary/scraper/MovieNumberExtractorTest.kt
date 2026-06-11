package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieNumberExtractorTest {
    @Test
    fun prefersLeadingSeparatedNumberOverTitleText() {
        val fileName = "IDBD-317(idbd00317)[IDEAPOCKET]IPSUPERGIRLSBEST100人16時間.strm"

        assertEquals("IDBD-317", MovieNumberExtractor.extract(fileName))
    }

    @Test
    fun stillIgnoresSitePrefixWhenRealNumberComesLater() {
        assertEquals("FNS-150", MovieNumberExtractor.extract("hhd800.com@FNS-150.MP4"))
        assertEquals("FNS-172", MovieNumberExtractor.extract("hhd800.com@FNS172.MP4"))
    }

    @Test
    fun extractsMgstageNumberWithNumericPrefix() {
        assertEquals("MIUM-111", MovieNumberExtractor.extract("300MIUM-111.strm"))
        assertEquals("SCUTE-953", MovieNumberExtractor.extract("229SCUTE-953.mp4"))
    }

    @Test
    fun extractsNumberWithTrailingLetterSuffix() {
        assertEquals("DANDY-852A", MovieNumberExtractor.extract("DANDY-852A.strm"))
    }

    @Test
    fun treatsConfiguredAttachedLettersAsSegments() {
        assertEquals("NHDTC-190", MovieNumberExtractor.extract("nhdtc-190a.mp4"))
        assertEquals("NHDTC-190", MovieNumberExtractor.extract("nhdtc-190b.mp4"))
    }

    @Test
    fun ignoresConfiguredTrailingNoiseSuffix() {
        assertEquals("MEYD-772", MovieNumberExtractor.extract("meyd00772hhb.strm"))
    }

    @Test
    fun supportsRemoteTrailingNoiseSuffixRules() {
        assertEquals("MEYD-772", MovieNumberExtractor.extract("meyd00772xyz.strm", ignoredSuffixes = setOf("XYZ")))
    }

    @Test
    fun extractsRestoredMovieNumbers() {
        assertEquals("ABF-308", MovieNumberExtractor.extract("ABF-308.restored.mp4"))
        assertEquals("JAC-233", MovieNumberExtractor.extract("390JAC-233.restored-A.mp4"))
        assertEquals("ABF-029", MovieNumberExtractor.extract("ABF-029.restored_iris2_watermarked.mp4"))
    }
}
