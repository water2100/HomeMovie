package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

class MgstageScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Mgstage

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeMgstageSearchNumber(number)
        val directUrl = movieUrl(normalized)
        runCatching {
            parseDetail(normalized, directUrl, fetchText(directUrl))
        }.getOrElse {
            val result = search(normalized).firstOrNull()
                ?: error("MGStage 没有搜索到结果：$normalized")
            parseDetail(normalized, result.homepage, fetchText(result.homepage))
        }
    }

    private fun search(keyword: String): List<SearchResult> {
        val url = "$SEARCH_URL${URLEncoder.encode(keyword.uppercase(Locale.ROOT), "UTF-8")}"
        val html = fetchText(url)
        return Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val href = Regex("""<h5\b[^>]*>[\s\S]*?<a\b[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { absoluteUrl(it, url) }
                    ?: return@mapNotNull null
                val id = movieIdFromUrl(href)
                if (id.isBlank()) return@mapNotNull null
                val title = firstLinkTextByClass(block, "title").ifBlank {
                    Regex("""<a\b[^>]*>\s*<p\b[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                        .find(block)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let(::cleanHtml)
                        .orEmpty()
                }
                SearchResult(id = id, homepage = href, title = title)
            }
            .distinctBy { it.id }
            .sortedByDescending { scoreSearchResult(it, keyword) }
            .toList()
    }

    private fun parseDetail(requestedNumber: String, url: String, html: String): ScrapedMovieInfo {
        val rows = parseInfoRows(html)
        val title = firstH1(html)
            .ifBlank { metaContent(html, "og:title") }
            .ifBlank { requestedNumber }
            .cleanText()
        val plot = metaContent(html, "og:description")
            .ifBlank { metaContent(html, "description") }
            .cleanText()
        val detailBlock = firstElementByClass(html, "detail_data")
        val thumbUrl = firstImgSrc(detailBlock)?.let { absoluteUrl(it, url) }.orEmpty()
        val bigThumbUrl = imageSrc(thumbUrl, thumb = true)
        val coverUrl = Regex("""<a\b[^>]+id=["']EnlargeImage["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { absoluteUrl(it, url) }
            .orEmpty()
        val bigCoverUrl = imageSrc(coverUrl, thumb = false)
        val number = rows.firstValue("品番").ifBlank { movieIdFromUrl(url).ifBlank { requestedNumber } }
        val release = rows.firstValue("配信開始日", "商品発売日").dateOrBlank()
        val runtime = rows.firstValue("収録時間").digitsOnly()
        val actors = rows.firstLinks("出演").ifEmpty {
            rows.firstValue("出演").splitNames()
        }
        val genres = rows.firstLinks("ジャンル")
        val maker = rows.firstValue("メーカー")
        val label = rows.firstValue("レーベル")
        val series = rows.firstValue("シリーズ")
        val score = rows.firstValue("評価").scoreOrBlank()
        val trailer = sampleMovieUrl(html)

        if (title.isBlank() && bigThumbUrl.isBlank() && bigCoverUrl.isBlank()) {
            error("MGStage 没有解析到有效详情：$requestedNumber")
        }

        return ScrapedMovieInfo(
            number = normalizeMgstageSearchNumber(number.ifBlank { requestedNumber }),
            title = title.ifBlank { requestedNumber },
            originalTitle = title.ifBlank { requestedNumber },
            plot = plot,
            outline = plot,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = runtime,
            studio = maker,
            publisher = label,
            series = series,
            actors = actors,
            genres = genres,
            tags = genres,
            rating = score,
            trailer = trailer,
            website = url,
            source = "mgstage",
            posterUrl = bigThumbUrl.ifBlank { thumbUrl },
            thumbUrl = bigCoverUrl.ifBlank { coverUrl },
            posterImageUrls = listOf(bigThumbUrl, thumbUrl),
            thumbImageUrls = listOf(bigCoverUrl, coverUrl)
        )
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ja,en-US;q=0.8,en;q=0.6")
            .header("Cookie", "adc=1")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("MGStage 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("MGStage 响应为空：$url")
            val bytes = body.bytes()
            String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "ja,en-US;q=0.8,en;q=0.6")
            .header("Cookie", "adc=1")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("MGStage 样片接口失败 HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun sampleMovieUrl(html: String): String {
        val sampleHref = Regex("""<p\b[^>]*class=["'][^"']*\bsample_movie_btn\b[^"']*["'][^>]*>[\s\S]*?<a\b[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (sampleHref.isBlank()) return ""
        val pid = sampleHref.substringBefore('?').trimEnd('/').substringAfterLast('/').trim()
        if (pid.isBlank()) return ""
        return runCatching {
            fetchJson("$SAMPLE_URL${URLEncoder.encode(pid, "UTF-8")}")
                .optString("url")
                .replace(Regex("""\.ism/request?.+$""", RegexOption.IGNORE_CASE), ".mp4")
                .cleanText()
        }.getOrDefault("")
    }

    private fun parseInfoRows(html: String): List<InfoRow> =
        Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { match ->
                val rowHtml = match.groupValues[1]
                val label = Regex("""<th\b[^>]*>([\s\S]*?)</th>""", RegexOption.IGNORE_CASE)
                    .find(rowHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::cleanHtml)
                    ?.normalizeLabel()
                    ?: return@mapNotNull null
                val tdHtml = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
                    .find(rowHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                val links = linksIn(tdHtml)
                val value = cleanHtml(tdHtml)
                if (label.isBlank() || (value.isBlank() && links.isEmpty())) null else InfoRow(label, value, links)
            }
            .toList()

    private fun List<InfoRow>.firstValue(vararg labels: String): String {
        val keys = labels.map { it.normalizeLabel() }.toSet()
        return firstOrNull { it.label in keys }?.value.orEmpty()
    }

    private fun List<InfoRow>.firstLinks(vararg labels: String): List<String> {
        val keys = labels.map { it.normalizeLabel() }.toSet()
        return firstOrNull { it.label in keys }?.links.orEmpty()
    }

    private fun firstElementByClass(html: String, classToken: String): String {
        val start = Regex("""<[a-z0-9]+[^>]*class=["'][^"']*\b${Regex.escape(classToken)}\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.range
            ?.first
            ?: return ""
        return html.substring(start, minOf(html.length, start + 12_000))
    }

    private fun firstH1(html: String): String =
        Regex("""<h1\b[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun firstImgSrc(html: String): String? =
        Regex("""<img\b[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }

    private fun firstLinkTextByClass(html: String, classToken: String): String =
        Regex("""<a\b[^>]*class=["'][^"']*\b${Regex.escape(classToken)}\b[^"']*["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun linksIn(html: String): List<String> =
        Regex("""<a\b[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun metaContent(html: String, name: String): String =
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun scoreSearchResult(result: SearchResult, keyword: String): Int {
        val normalizedKeyword = normalizeMgstageSearchNumber(keyword)
        var score = 0
        if (result.id.equals(normalizedKeyword, ignoreCase = true)) score += 200
        if (result.id.contains(normalizedKeyword, ignoreCase = true)) score += 100
        if (result.title.contains(normalizedKeyword, ignoreCase = true)) score += 20
        return score
    }

    private fun imageSrc(src: String, thumb: Boolean): String {
        if (src.isBlank()) return ""
        val replacement = if (thumb) "/pf_e_" else "/pb_e_"
        return src.replace(Regex("""(?i)/p[fb]_[a-z]\d*_"""), replacement)
    }

    private fun absoluteUrl(value: String, base: String = BASE_URL): String {
        val clean = value.cleanText()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("/") -> BASE_URL.trimEnd('/') + clean
            else -> base.substringBeforeLast('/', BASE_URL) + "/" + clean
        }
    }

    private fun movieUrl(movieId: String): String = "$MOVIE_URL${movieId.uppercase(Locale.ROOT)}/"

    private fun movieIdFromUrl(value: String): String =
        value.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .uppercase(Locale.ROOT)

    private fun String.normalizeLabel(): String =
        cleanText()
            .trimEnd(':', '：')
            .replace(Regex("""\s+"""), "")

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun String.dateOrBlank(): String {
        val match = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""").find(this)?.value ?: return ""
        val normalized = match.replace("/", "-")
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(normalized)?.let {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it)
            } ?: normalized
        }.getOrDefault(normalized)
    }

    private fun String.scoreOrBlank(): String =
        Regex("""\d+(?:\.\d+)?""").find(this)?.value.orEmpty()

    private fun String.splitNames(): List<String> =
        split(Regex("[,\\uFF0C\\u3001|;\\r\\n\\t ]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

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
            .replace("\r", " ")
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

    private data class SearchResult(
        val id: String,
        val homepage: String,
        val title: String
    )

    private data class InfoRow(
        val label: String,
        val value: String,
        val links: List<String>
    )

    private companion object {
        const val BASE_URL = "https://www.mgstage.com/"
        const val MOVIE_URL = "https://www.mgstage.com/product/product_detail/"
        const val SEARCH_URL = "https://www.mgstage.com/search/cSearch.php?search_word="
        const val SAMPLE_URL = "https://www.mgstage.com/sampleplayer/sampleRespons.php?pid="
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}

internal fun normalizeMgstageSearchNumber(
    number: String,
    prefixAliases: Map<String, String> = MGSTAGE_SEARCH_PREFIX_ALIASES
): String {
    val match = Regex("""(?i)([a-z0-9]{2,12}?)[-_ ]?(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""").find(number.trim())
        ?: return number.trim().uppercase(Locale.ROOT)
    val prefix = match.groupValues[1].uppercase(Locale.ROOT)
    if (!prefix.any { it.isLetter() }) return number.trim().uppercase(Locale.ROOT)
    val searchPrefix = normalizeMgstageSearchPrefixAliases(prefixAliases)[prefix] ?: prefix
    val suffix = match.groupValues[3].uppercase(Locale.ROOT)
    return "$searchPrefix-${match.groupValues[2]}$suffix"
}

private val MGSTAGE_SEARCH_PREFIX_ALIASES = mapOf(
    "SHN" to "116SHN",
    "DANDY" to "104DANDY",
    "GANA" to "200GANA",
    "SCUTE" to "229SCUTE",
    "LUXU" to "259LUXU",
    "ARA" to "261ARA",
    "DCV" to "277DCV",
    "MY" to "292MY",
    "EWDX" to "299EWDX",
    "MAAN" to "300MAAN",
    "MIUM" to "300MIUM",
    "NTK" to "300NTK",
    "KIRAY" to "314KIRAY",
    "KJO" to "326KJO",
    "NAMA" to "332NAMA",
    "KNB" to "336KNB",
    "SIMM" to "345SIMM",
    "NTR" to "348NTR",
    "ICHK" to "368ICHK",
    "JAC" to "390JAC",
    "KIWVR" to "408KIWVR",
    "INST" to "413INST",
    "SRYA" to "417SRYA",
    "SUKE" to "428SUKE",
    "MFC" to "435MFC",
    "HHH" to "451HHH",
    "TEN" to "459TEN",
    "MLA" to "476MLA",
    "SGK" to "483SGK",
    "GCB" to "485GCB",
    "SEI" to "502SEI",
    "STCV" to "529STCV"
)

private fun normalizeMgstageSearchPrefixAliases(aliases: Map<String, String>): Map<String, String> =
    (MGSTAGE_SEARCH_PREFIX_ALIASES + aliases)
        .mapNotNull { (key, value) ->
            val normalizedKey = key.normalizedMgstagePrefixOrNull() ?: return@mapNotNull null
            val normalizedValue = value.normalizedMgstagePrefixOrNull() ?: return@mapNotNull null
            normalizedKey to normalizedValue
        }
        .toMap()

private fun String.normalizedMgstagePrefixOrNull(): String? =
    trim()
        .uppercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
        .takeIf { value -> value.isNotBlank() && value.any { it.isLetter() } }
