package com.example.localmovielibrary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieVariantTest {
    @Test
    fun detectsCommonFourKMarkers() {
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118_4K.mp4"))
        assertEquals(MovieVariant.FourK60Fps, detectMovieVariant("FNS-118 4K60FPS.mp4"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118-2160p.mkv"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118 UHD.mp4"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("START-155_4Ks.mp4"))
    }

    @Test
    fun doesNotDetectEmbeddedTextAsFourK() {
        assertEquals(MovieVariant.Standard, detectMovieVariant("FNS-118_A4KCODE.mp4"))
        assertEquals(MovieVariant.Standard, detectMovieVariant("4k2.me@fns-128.mp4"))
        assertEquals(MovieVariant.Standard, detectMovieVariant("FNS-118.mp4"))
    }

    @Test
    fun detectsCommonEightKMarkers() {
        assertEquals(MovieVariant.EightK, detectMovieVariant("EBVR-00104.part4_8K.mp4"))
        assertEquals(MovieVariant.EightK60Fps, detectMovieVariant("EBVR-00104 8K60FPS.mp4"))
        assertEquals(MovieVariant.EightK, detectMovieVariant("EBVR-00104-4320p.mkv"))
    }

    @Test
    fun buildsPlaybackSourceSuffixWithPartAndVariant() {
        assertEquals("", playbackSourceSuffix(null, MovieVariant.Standard))
        assertEquals("-4K", playbackSourceSuffix(null, MovieVariant.FourK))
        assertEquals("-P4", playbackSourceSuffix("P4", MovieVariant.Standard))
        assertEquals("-P4-8K", playbackSourceSuffix("P4", MovieVariant.EightK))
    }
}
