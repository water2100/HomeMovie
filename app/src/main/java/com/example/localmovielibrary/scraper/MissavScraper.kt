package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.Locale

class MissavCookieRequiredException(
    val hasCookie: Boolean,
    message: String = if (hasCookie) {
        "MissAV Cookie 可能已失效，请到设置页重新获取 Cookie"
    } else {
        "MissAV 需要先获取 Cookie"
    }
) : RuntimeException(message)

class MissavScraper(
    private val cookieProvider: () -> String = { "" },
    private val languageProvider: () -> MissavScrapeLanguage = { MissavScrapeLanguage.Default },
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Missav

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        val language = languageProvider()
        val url = language.movieUrl(normalized)
        val html = fetch(url, language)
        if (isCloudflareChallenge(html)) {
            throw MissavCookieRequiredException(
                hasCookie = cookieProvider().trim().isNotBlank(),
                message = if (cookieProvider().trim().isNotBlank()) {
                    "MissAV 返回了 Cloudflare 验证页，已保存的 Cookie 可能失效"
                } else {
                    "MissAV 需要先获取 Cookie"
                }
            )
        }
        parseHtml(normalized, url, html)
    }

    fun scrapeFromHtml(number: String, html: String): ScrapedMovieInfo {
        val normalized = normalizeNumber(number)
        val url = languageProvider().movieUrl(normalized)
        if (isCloudflareChallenge(html)) {
            throw MissavCookieRequiredException(
                hasCookie = cookieProvider().trim().isNotBlank(),
                message = "隐藏 WebView 仍然拿到 Cloudflare 验证页，可能需要显示页面手动验证"
            )
        }
        return parseHtml(normalized, url, html)
    }

    fun isChallengePage(html: String): Boolean = isCloudflareChallenge(html)

    private fun parseHtml(normalized: String, url: String, html: String): ScrapedMovieInfo {
        val fields = parseInfoFields(html)
        val title = removeNumberPrefix(
            h1Title(html).ifBlank { metaContent(html, "og:title") },
            normalized
        ).ifBlank { fields["标题"].orEmpty() }
        val plot = description(html).ifBlank { metaContent(html, "description") }
        val imageSet = resolveImageSet(normalized)

        return ScrapedMovieInfo(
            number = normalized,
            title = title.ifBlank { normalized },
            originalTitle = title.ifBlank { normalized },
            plot = plot,
            outline = plot,
            premiered = fields["发行日期"].orEmpty(),
            year = Regex("""\d{4}""").find(fields["发行日期"].orEmpty())?.value.orEmpty(),
            studio = fields["发行商"].orEmpty(),
            publisher = fields["发行商"].orEmpty(),
            directors = splitList(fields["导演"].orEmpty()),
            actors = splitList(fields["女优"].orEmpty()).ifEmpty { splitList(fields["演员"].orEmpty()) },
            genres = splitList(fields["类型"].orEmpty()),
            tags = splitList(fields["标签"].orEmpty()).ifEmpty { splitList(fields["类型"].orEmpty()) },
            website = url,
            source = "missav",
            thumbUrl = imageSet.thumbUrl,
            posterUrl = imageSet.posterUrl
        )
    }

    private fun fetch(url: String, language: MissavScrapeLanguage): String {
        val cookie = cookieProvider().trim()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", language.acceptLanguage)
            .header("Referer", language.referer)
            .header("Origin", "https://missav.ai")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Sec-CH-UA", "\"Google Chrome\";v=\"147\", \"Chromium\";v=\"147\", \"Not=A?Brand\";v=\"24\"")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-CH-UA-Platform", "\"Windows\"")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
        if (cookie.isNotBlank()) {
            builder.header("Cookie", cookie)
        }

        return client.newCall(builder.build()).execute().use { response ->
            if (response.code == 403) {
                throw MissavCookieRequiredException(
                    hasCookie = cookie.isNotBlank(),
                    message = if (cookie.isNotBlank()) {
                        "MissAV 返回 403，Cookie 可能已失效或 User-Agent 不匹配"
                    } else {
                        "MissAV 返回 403，需要先获取 Cookie"
                    }
                )
            }
            if (!response.isSuccessful) error("MissAV 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("MissAV 响应为空: $url")
            val bytes = body.bytes()
            String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
        }
    }

    private fun resolveImageSet(number: String): ImageSet {
        val imageId = imageId(number).lowercase(Locale.ROOT)
        val poster = "https://awsimgsrc.dmm.co.jp/pics_dig/digital/video/$imageId/${imageId}ps.jpg"
        val large = "https://awsimgsrc.dmm.co.jp/pics_dig/digital/video/$imageId/${imageId}pl.jpg"
        return ImageSet(posterUrl = poster, thumbUrl = large)
    }

    private fun parseInfoFields(html: String): Map<String, String> {
        val box = firstDivByClass(html, "space-y-2")
        if (box.isBlank()) return emptyMap()
        return divsByClass(box, "text-secondary").mapNotNull { row ->
            val spans = Regex("""<span[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
                .findAll(row)
                .map { cleanHtml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .toList()
            val key = spans.firstOrNull()?.trimEnd(':', '：') ?: return@mapNotNull null
            val value = Regex("""<time[^>]*>([\s\S]*?)</time>""", RegexOption.IGNORE_CASE)
                .find(row)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanHtml)
                ?: linksIn(row).joinToString(", ")
                    .ifBlank { spans.drop(1).joinToString(" ") }
            key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
        }.toMap()
    }

    private fun h1Title(html: String): String =
        Regex("""<h1[^>]*class=["'][^"']*\btext-nord6\b[^"']*["'][^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun description(html: String): String =
        Regex("""<div[^>]*class=["'][^"']*\bmb-1\b[^"']*\btext-secondary\b[^"']*\bbreak-all\b[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun metaContent(html: String, name: String): String =
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun firstDivByClass(html: String, classToken: String): String {
        val start = Regex("""<div[^>]*class=["'][^"']*\b${Regex.escape(classToken)}\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.range
            ?.first
            ?: return ""
        return extractBalancedDiv(html, start)
    }

    private fun divsByClass(html: String, classToken: String): List<String> =
        Regex("""<div[^>]*class=["'][^"']*\b${Regex.escape(classToken)}\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { extractBalancedDiv(html, it.range.first) }
            .toList()

    private fun extractBalancedDiv(html: String, start: Int): String {
        val tagRegex = Regex("""</?div\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 0
        tagRegex.findAll(html, start).forEach { match ->
            if (match.value.startsWith("</", ignoreCase = true)) {
                depth -= 1
                if (depth == 0) return html.substring(start, match.range.last + 1)
            } else {
                depth += 1
            }
        }
        return html.substring(start, minOf(html.length, start + 2000))
    }

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun removeNumberPrefix(title: String, number: String): String =
        if (title.uppercase(Locale.ROOT).startsWith(number.uppercase(Locale.ROOT))) {
            title.drop(number.length).trim()
        } else {
            title
        }

    private fun imageId(number: String): String {
        val match = Regex("""(?i)^([a-z]+)[-_ ]?(\d+)$""").find(number.trim())
            ?: return number.lowercase(Locale.ROOT).replace("-", "")
        return match.groupValues[1].lowercase(Locale.ROOT) + match.groupValues[2].padStart(5, '0')
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,10})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    }

    private fun splitList(value: String): List<String> =
        value.split(Regex("""[,，、;/\r\n\t]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)
        return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower)
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val charsetName = Regex("""charset=["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
        return runCatching { charsetName?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
    }

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\t", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private data class ImageSet(
        val posterUrl: String,
        val thumbUrl: String
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    }
}
