package com.example.localmovielibrary.playback

const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"

const val DEFAULT_USER_AGENT = USER_AGENT

data class PlaybackRequest(
    val mediaUri: String,
    val title: String?,
    val userAgent: String?,
    val referer: String?,
    val cookie: String?,
    val pickcode: String? = null,
    val headers: Map<String, String> = emptyMap()
)
