package com.example.localmovielibrary.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class XunleiSubtitleRepositoryTest {
    @Test
    fun normalizesNumberWithMinimumThreeDigits() {
        assertEquals("MIAB-043", normalizeXunleiSubtitleNumber("MIAB-043"))
        assertEquals("MIAB-043", normalizeXunleiSubtitleNumber("MIAB-43"))
        assertEquals("MIAB-001", normalizeXunleiSubtitleNumber("miab001"))
    }

    @Test
    fun keepsLongerDigitWidth() {
        assertEquals("VRPRD-00156", normalizeXunleiSubtitleNumber("VRPRD-00156"))
    }
}
