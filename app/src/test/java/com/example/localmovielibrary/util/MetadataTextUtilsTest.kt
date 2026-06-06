package com.example.localmovielibrary.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTextUtilsTest {
    @Test
    fun exactMetadataMatchRequiresWholeValue() {
        val actors = listOf("FNS-150", "女神ジュン")

        assertTrue(actors.containsMetadataValue("女神ジュン", exact = true))
        assertFalse(actors.containsMetadataValue("FNS", exact = true))
    }

    @Test
    fun fuzzyMetadataMatchAllowsPartialValue() {
        val genres = listOf("Drama Idol", "VR")

        assertTrue(genres.containsMetadataValue("idol", exact = false))
        assertTrue(genres.containsMetadataValue(" drama   idol ", exact = false))
    }
}
