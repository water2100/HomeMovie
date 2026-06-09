package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DmmScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Dmm

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = number.uppercase()
        val detailUrl = search(normalized)
        val html = fetch(detailUrl)
        parseDetail(html, detailUrl, normalized)
    }

    private fun search(number: String): String {
        val urls = listOf(
            "https://www.dmm.co.jp/search/=/searchstr=$number/",
            "https://www.dmm.co.jp/mono/dvd/-/search/=/searchstr=$number/",
            "https://www.dmm.co.jp/digital/videoa/-/list/search/=/?searchstr=$number"
        )
        for (url in urls) {
            val html = fetch(url)
            val link = Regex("""<a[^>]+href=["']([^"']*/detail/=/cid=[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            if (!link.isNullOrBlank()) {
                return when {
                    link.startsWith("//") -> "https:$link"
                    link.startsWith("/") -> "https://www.dmm.co.jp$link"
                    else -> link
                }
            }
        }
        error("DMM 没有搜索到详情页：$number")
    }

    private fun parseDetail(html: String, detailUrl: String, number: String): ScrapedMovieInfo {
        val title = textByRegex(html, Regex("""<h1[^>]*(?:id=["']title["']|class=["'][^"']*(?:item|fn|bold)[^"']*["'])[^>]*>(.*?)</h1>""", RegexOption.IGNORE_CASE))
            .ifBlank { textByRegex(html, Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) }
        if (title.isBlank()) error("DMM 详情页没有解析到标题")
        val thumb = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("ps.jpg", "pl.jpg").orEmpty()
        val poster = buildPosterUrl(thumb)
        val imageCandidates = buildDigitalImageCandidates(number, thumb, poster.ifBlank { thumb })
        val tags = linksNearLabel(html, "ジャンル")
        val actors = Regex("""<(?:span|td)[^>]+(?:id=["']performer["']|id=["']fn-visibleActor["'])[\s\S]*?</(?:span|td)>""", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?.let { linksIn(it) }
            .orEmpty()
            .ifEmpty { linksNearLabel(html, "出演者") }

        val release = textNearLabel(html, "発売日")
            .ifBlank { textNearLabel(html, "配信開始日") }
            .replace("/", "-")
        val runtime = textNearLabel(html, "収録時間").digitsOnly()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = textByRegex(html, Regex("""<div[^>]+class=["'][^"']*(?:mg-b20|clear|wrapper-detailContents)[^"']*["'][^>]*>\s*<p[^>]*>(.*?)</p>""", RegexOption.IGNORE_CASE)),
            premiered = release,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            runtime = runtime,
            studio = linksNearLabel(html, "メーカー").firstOrNull().orEmpty(),
            publisher = linksNearLabel(html, "レーベル").firstOrNull().orEmpty(),
            series = linksNearLabel(html, "シリーズ").firstOrNull().orEmpty(),
            directors = linksNearLabel(html, "監督"),
            actors = actors,
            genres = tags,
            tags = tags,
            rating = textByRegex(html, Regex("""d-review__average[\s\S]*?<strong[^>]*>(.*?)</strong>""", RegexOption.IGNORE_CASE)),
            trailer = Regex("""video_url["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty(),
            website = detailUrl,
            source = "dmm",
            thumbUrl = thumb,
            posterUrl = poster.ifBlank { thumb },
            thumbImageUrls = imageCandidates.thumbUrls,
            posterImageUrls = imageCandidates.posterUrls
        )
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cookie", "age_check_done=1")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("DMM 请求失败 HTTP ${response.code}: $url")
            response.body?.string().orEmpty()
        }
    }

    private fun linksNearLabel(html: String, label: String): List<String> {
        val area = Regex("""$label[\s\S]{0,900}?(?:</tr>|</table>|</div>\s*</div>)""", RegexOption.IGNORE_CASE)
            .find(html)?.value.orEmpty()
        return linksIn(area)
    }

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun textNearLabel(html: String, label: String): String {
        val area = Regex("""$label[\s\S]{0,260}?(?:</tr>|</td>|</div>)""", RegexOption.IGNORE_CASE).find(html)?.value.orEmpty()
        return cleanHtml(Regex("""</(?:td|th|div)>\s*<[^>]+>\s*([^<]+)""", RegexOption.IGNORE_CASE).find(area)?.groupValues?.getOrNull(1).orEmpty())
    }

    private fun textByRegex(html: String, regex: Regex): String =
        cleanHtml(regex.find(html)?.groupValues?.getOrNull(1).orEmpty())

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun buildPosterUrl(thumbUrl: String): String =
        thumbUrl.replace(Regex("""pl(\.(jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE), "ps\$1")

    private fun buildDigitalImageCandidates(
        number: String,
        fallbackThumb: String,
        fallbackPoster: String
    ): DmmImageCandidates {
        val contentId = buildDigitalContentId(number) ?: return DmmImageCandidates(
            thumbUrls = listOf(fallbackThumb).normalizedUrlList(),
            posterUrls = listOf(fallbackPoster).normalizedUrlList()
        )
        val baseUrl = "$AWS_IMAGE_BASE_URL/$contentId"
        return DmmImageCandidates(
            thumbUrls = listOf(
                "$baseUrl/${contentId}pl.jpg",
                "$AWS_IMAGE_BASE_URL/1$contentId/1${contentId}pl.jpg",
                fallbackThumb
            ).normalizedUrlList(),
            posterUrls = listOf(
                "$baseUrl/${contentId}ps.jpg",
                "$AWS_IMAGE_BASE_URL/1$contentId/1${contentId}ps.jpg",
                fallbackPoster
            ).normalizedUrlList()
        )
    }

    private fun buildDigitalContentId(number: String): String? {
        val match = Regex("""(?i)^([a-z]+)[-_ ]?0*(\d+)$""").find(number.trim()) ?: return null
        val prefix = match.groupValues[1].lowercase()
        val digits = match.groupValues[2].trimStart('0').ifBlank { "0" }
        return prefix + digits.padStart(5, '0')
    }

    private fun List<String>.normalizedUrlList(): List<String> =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .trim()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val AWS_IMAGE_BASE_URL = "https://awsimgsrc.dmm.co.jp/pics_dig/digital/video"
    }

    private data class DmmImageCandidates(
        val thumbUrls: List<String>,
        val posterUrls: List<String>
    )
}
