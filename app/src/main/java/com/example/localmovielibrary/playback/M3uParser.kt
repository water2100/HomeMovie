package com.example.localmovielibrary.playback

data class ParsedM3u(
    val mediaUrl: String,
    val userAgent: String?,
    val referer: String?,
    val cookie: String?
)

class M3uParser {
    fun parse(text: String): Result<ParsedM3u> = runCatching {
        var mediaUrl: String? = null
        var userAgent: String? = null
        var referer: String? = null
        var cookie: String? = null

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val option = line.substringAfter(':')
                        when {
                            option.startsWith("http-user-agent=", ignoreCase = true) ->
                                userAgent = option.substringAfter('=').trim()
                            option.startsWith("http-referrer=", ignoreCase = true) ->
                                referer = option.substringAfter('=').trim()
                            option.startsWith("http-cookie=", ignoreCase = true) ->
                                cookie = option.substringAfter('=').trim()
                        }
                    }
                    line.startsWith("#") -> Unit
                    mediaUrl == null && line.isHttpUrl() -> mediaUrl = line
                }
            }

        ParsedM3u(
            mediaUrl = mediaUrl ?: error("No playable media URL found in M3U"),
            userAgent = userAgent,
            referer = referer,
            cookie = cookie
        )
    }
}

internal fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
