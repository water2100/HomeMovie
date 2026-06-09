package com.example.localmovielibrary.playback

import android.content.ContentResolver
import android.net.Uri
import com.example.localmovielibrary.data.repository.DirectLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlaybackResolver(
    private val contentResolver: ContentResolver,
    private val directLinkRepository: DirectLinkRepository,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    private val strmParser = StrmParser(contentResolver)
    private val m3uParser = M3uParser()

    suspend fun resolve(
        mediaUri: String,
        title: String?,
        fileName: String?,
        forceRefresh: Boolean = false
    ): Result<PlaybackRequest> =
        withContext(Dispatchers.IO) {
            runCatching {
                PickcodeExtractor.extract(mediaUri)?.let { pickcode ->
                    return@runCatching resolve115Pickcode(pickcode, title, forceRefresh)
                }

                val isStrm = fileName?.substringAfterLast('.', "")?.equals("strm", ignoreCase = true) == true ||
                    mediaUri.substringBefore('?').lowercase(Locale.ROOT).endsWith(".strm")

                if (!isStrm) {
                    return@runCatching directRequest(mediaUri, title)
                }

                val entryUrl = strmParser.readEntryUrl(Uri.parse(mediaUri)).getOrElse { throw it }
                if (!entryUrl.isHttpUrl()) {
                    return@runCatching directRequest(entryUrl, title)
                }

                val pickcode = PickcodeExtractor.extract(entryUrl)
                if (pickcode != null) {
                    return@runCatching resolve115Pickcode(pickcode, title, forceRefresh)
                }

                resolveNetworkEntry(entryUrl, title)
            }
        }

    suspend fun invalidatePickcode(pickcode: String) {
        directLinkRepository.invalidate(pickcode)
    }

    private suspend fun resolve115Pickcode(
        pickcode: String,
        title: String?,
        forceRefresh: Boolean
    ): PlaybackRequest {
        val directUrl = directLinkRepository.resolve(pickcode, forceRefresh)
        return PlaybackRequest(
            mediaUri = directUrl,
            title = title,
            userAgent = USER_AGENT,
            referer = null,
            cookie = null,
            pickcode = pickcode,
            headers = mapOf("User-Agent" to USER_AGENT)
        )
    }

    private fun resolveNetworkEntry(entryUrl: String, title: String?): PlaybackRequest {
        val request = Request.Builder()
            .url(entryUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-262143")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Unable to fetch playback address")

            val contentType = response.header("Content-Type").orEmpty()
            val preview = response.peekBody(256 * 1024).string()
            val looksLikeM3u = isM3uResponse(entryUrl, contentType, preview)

            if (!looksLikeM3u) {
                return directRequest(entryUrl, title)
            }

            val parsed = m3uParser.parse(preview).getOrElse {
                throw IllegalStateException(it.message ?: "No playable media URL found in M3U")
            }
            val headers = buildHeaders(parsed.userAgent, parsed.referer, parsed.cookie)
            return PlaybackRequest(
                mediaUri = parsed.mediaUrl,
                title = title,
                userAgent = parsed.userAgent,
                referer = parsed.referer,
                cookie = parsed.cookie,
                headers = headers
            )
        }
    }

    private fun directRequest(mediaUri: String, title: String?): PlaybackRequest =
        PlaybackRequest(
            mediaUri = mediaUri,
            title = title,
            userAgent = null,
            referer = null,
            cookie = null,
            headers = emptyMap()
        )

    private fun isM3uResponse(url: String, contentType: String, bodyPreview: String): Boolean {
        val lowerUrl = url.substringBefore('?').lowercase(Locale.ROOT)
        val lowerType = contentType.lowercase(Locale.ROOT)
        return lowerUrl.endsWith(".m3u") ||
            lowerUrl.endsWith(".m3u8") ||
            lowerType.contains("application/vnd.apple.mpegurl") ||
            lowerType.contains("application/x-mpegurl") ||
            lowerType.contains("audio/x-mpegurl") ||
            lowerType.contains("audio/mpegurl") ||
            lowerType.contains("text/plain") ||
            bodyPreview.trimStart().startsWith("#EXTM3U", ignoreCase = true)
    }

    private fun buildHeaders(userAgent: String?, referer: String?, cookie: String?): Map<String, String> =
        buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
}
