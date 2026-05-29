package com.example.localmovielibrary.playback

import android.net.Uri

object DirectLinkExpiryParser {
    fun parseExpiresAt(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        val rawT = runCatching { Uri.parse(url).getQueryParameter("t") }.getOrNull()
        val expires = rawT?.toLongOrNull()
        return if (expires != null) {
            (expires - 600L).coerceAtLeast(nowSeconds)
        } else {
            nowSeconds + 7200L
        }
    }
}
