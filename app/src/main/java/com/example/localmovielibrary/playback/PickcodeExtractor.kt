package com.example.localmovielibrary.playback

import android.net.Uri

object PickcodeExtractor {
    private val supportedRoutes = setOf("download_m3u", "play", "video_proxy")

    fun extract(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.host in supportedRoutes) {
            return uri.pathSegments.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        val segments = uri.pathSegments
        for (index in segments.indices) {
            if (segments[index] in supportedRoutes && index + 1 < segments.size) {
                return segments[index + 1].trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
