package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.diagnostics.RuntimeErrorLog
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class AvsubtitlesCloudflareException(message: String) : IllegalStateException(message)

class AvsubtitlesSubtitleRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val errorLog = RuntimeErrorLog(appContext)
    private val fileStore = LocalSubtitleStore(appContext, settingsRepository)

    suspend fun search(number: String, videoDurationMs: Long): List<SubtitleSearchResult> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeDefaultSubtitleNumber(number).lowercase(Locale.ROOT)
            if (normalized.isBlank()) return@withContext emptyList()
            val searchUrl = "$BASE_URL/search_results.php".toHttpUrl().newBuilder()
                .addQueryParameter("search", normalized)
                .build()
            val searchHtml = getHtml(searchUrl.toString(), "avsubtitles.search")
            val movieLinks = parseMovieLinks(searchHtml, normalized)
            errorLog.append(
                event = "avsubtitles.search.movies",
                details = mapOf(
                    "number" to normalized,
                    "movieCount" to movieLinks.size.toString(),
                    "videoDurationMs" to videoDurationMs.toString()
                )
            )
            movieLinks
                .take(MAX_MOVIE_PAGES)
                .flatMap { movieLink ->
                    val movieHtml = getHtml("$BASE_URL$movieLink", "avsubtitles.movie")
                    parseSubtitleLinks(movieHtml, movieLink)
                }
                .distinctBy { "${it.language}:${it.cid}" }
                .take(MAX_SUBTITLE_RESULTS)
        }

    suspend fun download(
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = withContext(Dispatchers.IO) {
        val revid = result.timestamp.takeIf { it > 0L }
            ?: error("AVSubtitles 下载信息不完整，缺少 revid")
        val url = "$BASE_URL/download_sub.php".toHttpUrl().newBuilder()
            .addQueryParameter("subid", result.cid)
            .addQueryParameter("revid", revid.toString())
            .build()
        val bytes = getBytes(url.toString(), "avsubtitles.download")
        val extracted = extractSubtitleFromZip(bytes)
            ?: error("AVSubtitles 下载结果中没有找到可用字幕文件")
        val storeResult = result.copy(
            name = extracted.name,
            ext = extracted.ext,
            provider = SubtitleSearchProvider.Avsubtitles
        )
        fileStore.saveSubtitleBytes(
            videoUri = videoUri,
            fileName = fileName,
            result = storeResult,
            bytes = extracted.bytes,
            storageSourceUri = storageSourceUri
        )
    }

    private fun parseMovieLinks(html: String, normalizedNumber: String): List<String> {
        val lower = normalizedNumber.lowercase(Locale.ROOT)
        return MOVIE_LINK_REGEX.findAll(html)
            .map { it.groupValues[1].htmlDecodeLite() }
            .filter { link ->
                link.lowercase(Locale.ROOT).contains(lower) ||
                    link.lowercase(Locale.ROOT).contains(lower.replace("-", ""))
            }
            .distinct()
            .toList()
    }

    private fun parseSubtitleLinks(movieHtml: String, movieLink: String): List<SubtitleSearchResult> {
        val rows = SUBTITLE_ROW_REGEX.findAll(movieHtml)
            .map { it.value }
            .filter { SUBTITLE_LINK_REGEX.containsMatchIn(it) }
            .toList()
            .ifEmpty { listOf(movieHtml) }
        return rows.asSequence()
            .flatMap { row -> SUBTITLE_LINK_REGEX.findAll(row).map { row to it } }
            .mapNotNull { match ->
                val row = match.first
                val linkMatch = match.second
                val link = linkMatch.groupValues[1].htmlDecodeLite()
                val language = linkMatch.groupValues[3].uppercase(Locale.ROOT)
                val subId = linkMatch.groupValues[4]
                val subtitleName = parseSubtitleDisplayName(row)
                    ?: "$language subtitle $subId"
                val detailHtml = runCatching {
                    getHtml("$BASE_URL$link", "avsubtitles.subtitle")
                }.getOrElse { error ->
                    errorLog.append(
                        event = "avsubtitles.subtitle.detail.failed",
                        details = mapOf("link" to link, "subId" to subId),
                        error = error
                    )
                    return@mapNotNull null
                }
                val revid = parseRevid(detailHtml)
                if (revid == null) {
                    errorLog.append(
                        event = "avsubtitles.subtitle.revid.missing",
                        details = mapOf("link" to link, "subId" to subId)
                    )
                    return@mapNotNull null
                }
                val fileName = parseZipName(detailHtml)
                    ?: "avsubtitles-$subId-$revid.zip"
                SubtitleSearchResult(
                    cid = subId,
                    ext = "zip",
                    name = subtitleName,
                    durationMs = null,
                    language = language,
                    extraName = fileName,
                    timestamp = revid,
                    signature = link,
                    provider = SubtitleSearchProvider.Avsubtitles
                )
            }
            .distinctBy { it.cid }
            .toList()
    }

    private fun parseSubtitleDisplayName(rowHtml: String): String? {
        val cells = TD_REGEX.findAll(rowHtml)
            .map { it.groupValues[1].stripTags().htmlDecodeLite().trim() }
            .filter { it.isNotBlank() }
            .toList()
        return cells.firstOrNull { cell ->
            cell.contains("-", ignoreCase = true) &&
                !cell.contains("Info / Download", ignoreCase = true)
        } ?: cells.getOrNull(2)
    }

    private fun parseRevid(html: String): Long? {
        DOWNLOAD_URL_REVID_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        ZIP_REVID_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        return null
    }

    private fun parseZipName(html: String): String? =
        ZIP_NAME_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.htmlDecodeLite()
            ?.takeIf { it.isNotBlank() }

    private fun getHtml(url: String, event: String): String {
        val bytes = getBytes(url, event)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun getBytes(url: String, event: String): ByteArray {
        val cookie = settingsRepository.getAvsubtitlesCookies()
        errorLog.append(
            event = "$event.request",
            details = mapOf(
                "url" to url,
                "hasCookie" to cookie.isNotBlank().toString(),
                "cookieLength" to cookie.length.toString()
            )
        )
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
            }
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val preview = bytes.take(4096).toByteArray().toString(Charsets.UTF_8)
            if (response.code == 403 || preview.isAvsubtitlesChallengeHtml()) {
                throw AvsubtitlesCloudflareException("AVSubtitles 需要通过浏览器验证后获取 Cookie")
            }
            if (!response.isSuccessful) error("AVSubtitles 请求失败：HTTP ${response.code}")
            if (bytes.isEmpty()) error("AVSubtitles 请求结果为空")
            return bytes
        }
    }

    private fun extractSubtitleFromZip(bytes: ByteArray): ExtractedSubtitle? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in SUPPORTED_SUBTITLE_EXTENSIONS) continue
                val subtitleBytes = zip.readBytes()
                if (subtitleBytes.isNotEmpty()) {
                    return ExtractedSubtitle(name = name, ext = ext, bytes = subtitleBytes)
                }
            }
        }
    }

    private fun String.isAvsubtitlesChallengeHtml(): Boolean {
        val text = lowercase(Locale.ROOT)
        return text.contains("cloudflare") ||
            text.contains("cf-challenge") ||
            text.contains("captcha") ||
            text.contains("checking your browser")
    }

    private fun String.htmlDecodeLite(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun String.stripTags(): String =
        replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ")

    private data class ExtractedSubtitle(
        val name: String,
        val ext: String,
        val bytes: ByteArray
    )

    companion object {
        private const val BASE_URL = "https://www.avsubtitles.com"
        private const val MAX_MOVIE_PAGES = 3
        private const val MAX_SUBTITLE_RESULTS = 12
        private val MOVIE_LINK_REGEX = Regex("""href=["'](/movie\d+/[^"']+)["']""", RegexOption.IGNORE_CASE)
        private val SUBTITLE_ROW_REGEX = Regex("""<tr\b[^>]*>.*?</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SUBTITLE_LINK_REGEX = Regex(
            """href=["']((/movie\d+/[^"']+/subtitles/([a-z]{2,8})/(\d+)))["']""",
            RegexOption.IGNORE_CASE
        )
        private val TD_REGEX = Regex("""<td\b[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val DOWNLOAD_URL_REVID_REGEX = Regex("""download_sub\.php\?subid=\d+&revid=(\d+)""", RegexOption.IGNORE_CASE)
        private val ZIP_REVID_REGEX = Regex("""-(\d{10,})\.zip""", RegexOption.IGNORE_CASE)
        private val ZIP_NAME_REGEX = Regex(""">([^<>]+\.zip)<""", RegexOption.IGNORE_CASE)
        private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    }
}
