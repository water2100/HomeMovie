package com.example.localmovielibrary.subtitle

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextCodecTest {
    @Test
    fun `GB18030 SRT is decoded as readable Chinese and requires UTF-8 normalization`() {
        val original = "1\n00:00:14,484 --> 00:00:15,542\n我回来了\n"
        val decoded = decodeSubtitleBytes(original.toByteArray(Charset.forName("GB18030")))

        assertEquals(original, decoded.text)
        assertFalse(decoded.isUtf8)
    }

    @Test
    fun `UTF-8 subtitle is kept as UTF-8`() {
        val original = "1\n00:00:14,484 --> 00:00:15,542\n我回来了\n"
        val decoded = decodeSubtitleBytes(original.toByteArray(Charsets.UTF_8))

        assertEquals(original, decoded.text)
        assertTrue(decoded.isUtf8)
    }
}
