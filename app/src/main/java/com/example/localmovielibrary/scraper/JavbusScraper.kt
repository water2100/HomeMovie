package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.Locale

class JavbusScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Javbus

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        val url = "$BASE_URL/$normalized"
        val html = fetch(url)
        parseDetail(normalized, url, html)
    }

    private fun parseDetail(number: String, url: String, html: String): ScrapedMovieInfo {
        val title = h3Title(html).ifBlank { titleTag(html) }
            .removeSuffix(" - JavBus")
            .cleanText()
        if (title.isBlank()) error("JavBus 没有解析到标题：$number")

        val coverUrl = absoluteUrl(
            Regex("""<a[^>]+class=["'][^"']*\bbigImage\b[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        )
        val posterUrl = buildPosterUrl(coverUrl)
        val fields = parseInfoFields(html)
        val actors = parseActors(html)
        val actorImageUrls = parseActorImageUrls(html)
        val genres = parseGenres(html)
        val release = fields.firstValue("發行日期", "发行日期")
        val runtime = fields.firstValue("長度", "长度").digitsOnly()
        val plot = metaContent(html, "description").cleanText()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = plot,
            outline = plot,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = runtime,
            studio = fields.firstValue("製作商", "制作商"),
            publisher = fields.firstValue("發行商", "发行商"),
            series = fields.firstValue("系列"),
            directors = fields.firstValue("導演", "导演").splitNames(),
            actors = actors,
            actorImageUrls = actorImageUrls,
            genres = genres,
            tags = genres,
            website = url,
            source = "javbus",
            thumbUrl = coverUrl,
            posterUrl = posterUrl.ifBlank { coverUrl }
        )
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Priority", "u=0, i")
            .header("Sec-CH-UA", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-CH-UA-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("JavBus 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("JavBus 响应为空：$url")
            val bytes = body.bytes()
            String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
        }
    }

    private fun parseInfoFields(html: String): Map<String, String> {
        val infoBlock = Regex(
            """<div[^>]+class=["'][^"']*\binfo\b[^"']*["'][^>]*>([\s\S]*?)(?:<p class=["']star-show|</div>\s*</div>)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        return Regex(
            """<p[^>]*>\s*<span[^>]+class=["'][^"']*\bheader\b[^"']*["'][^>]*>([\s\S]*?)</span>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE
        ).findAll(infoBlock)
            .mapNotNull { match ->
                val key = cleanHtml(match.groupValues[1]).trimEnd(':', '：')
                val valueHtml = match.groupValues[2]
                val value = linksIn(valueHtml).firstOrNull().orEmpty()
                    .ifBlank { cleanHtml(valueHtml) }
                    .trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun parseGenres(html: String): List<String> =
        Regex(
            """<span[^>]+class=["'][^"']*\bgenre\b[^"']*["'][^>]*>\s*<label>[\s\S]*?<a[^>]*>([\s\S]*?)</a>[\s\S]*?</label>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() && it != "多選提交" }
            .distinct()
            .toList()

    private fun parseActors(html: String): List<String> =
        Regex(
            """<div[^>]+class=["'][^"']*\bstar-name\b[^"']*["'][^>]*>\s*<a[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun parseActorImageUrls(html: String): Map<String, String> =
        Regex(
            """<div[^>]+class=["'][^"']*\bstar-box\b[^"']*["'][^>]*>[\s\S]*?<img[^>]+src=["']([^"']+)["'][^>]+title=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { match ->
                val url = absoluteUrl(match.groupValues[1])
                val name = cleanHtml(match.groupValues[2])
                if (name.isBlank() || url.isBlank()) null else name to url
            }
            .distinctBy { it.first }
            .toMap()

    private fun h3Title(html: String): String =
        Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun titleTag(html: String): String =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun metaContent(html: String, name: String): String =
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun buildPosterUrl(coverUrl: String): String {
        if (coverUrl.isBlank()) return ""
        val match = Regex("""/pics/cover/([^/?#]+)_b(\.[a-z0-9]+)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(coverUrl)
            ?: return ""
        return "$BASE_URL/pics/thumb/${match.groupValues[1]}${match.groupValues[2]}"
    }

    private fun absoluteUrl(value: String): String {
        val clean = value.cleanText()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$BASE_URL$clean"
            clean.startsWith("http", ignoreCase = true) -> clean
            else -> "$BASE_URL/$clean"
        }
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,12})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        val digits = match.groupValues[2].trimStart('0').ifBlank { "0" }.padStart(3, '0')
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-$digits"
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun String.splitNames(): List<String> =
        split(Regex("[,，、|;\\r\\n\\t]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun String.cleanText(): String = cleanHtml(this)

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace("\r", "")
            .replace("\n", " ")
            .replace("\t", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val charsetName = Regex("""charset=["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
        return runCatching { charsetName?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
    }

    private companion object {
        const val BASE_URL = "https://www.javbus.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
    }
}
