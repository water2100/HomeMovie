package com.example.localmovielibrary.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class XunleiSubtitleRepositoryTest {
    @Test
    fun normalizesNumberWithMinimumThreeDigits() {
        assertEquals("miab-043", normalizeXunleiSubtitleNumber("MIAB-043"))
        assertEquals("miab-043", normalizeXunleiSubtitleNumber("MIAB-43"))
        assertEquals("miab-001", normalizeXunleiSubtitleNumber("miab001"))
        assertEquals("waaa-315", normalizeXunleiSubtitleNumber("WAAA-315"))
    }

    @Test
    fun keepsLongerDigitWidth() {
        assertEquals("vrprd-00156", normalizeXunleiSubtitleNumber("VRPRD-00156"))
    }
}
