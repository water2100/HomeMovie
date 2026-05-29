package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset

class OfficialScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Official

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = number.uppercase()
        val officialUrl = findOfficialUrl(normalized)
            ?: error("番号前缀不在 official 支持列表中：${prefixOf(normalized)}")
        val searchUrl = "${officialUrl.trimEnd('/')}/search/list?keyword=${normalized.replace("-", "")}"
        val searchHtml = fetch(searchUrl)
        val real = findRealUrl(searchHtml, officialUrl, normalized)
            ?: error("official 搜索结果未匹配到番号：$normalized")
        val html = fetch(real.detailUrl)
        parseDetail(html, officialUrl, real.detailUrl, normalized, real.posterUrl)
    }

    private fun parseDetail(
        html: String,
        officialUrl: String,
        detailUrl: String,
        number: String,
        posterFromSearch: String
    ): ScrapedMovieInfo {
        val title = textByRegex(
            html,
            Regex("""<h2[^>]*class=["'][^"']*\bp-workPage__title\b[^"']*["'][^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)
        ).ifBlank {
            textByRegex(html, Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE))
        }
        if (title.isBlank()) error("official 详情页没有解析到标题")

        val images = Regex("""<img[^>]+class=["'][^"']*\bswiper-lazy\b[^"']*["'][^>]+(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].toAbsoluteUrl(officialUrl) }
            .distinct()
            .toList()
        val cover = images.firstOrNull().orEmpty()
        val outline = textByRegex(
            html,
            Regex("""<p[^>]*class=["'][^"']*\bp-workPage__text\b[^"']*["'][^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        )
        val release = firstLinkTextAfterLabel(html, "発売日")
            .replace("年", "-")
            .replace("月", "-")
            .replace("日", "")
            .trim()
        val publisherAndStudio = publisherAndStudio(html)
        val studio = publisherAndStudio.second.ifBlank { plainTextAfterLabel(html, "製作商") }
        val tags = linkTextsAfterLabel(html, "ジャンル")
            .filterNot { it == "Blu-ray（ブルーレイ）" }
            .distinct()
        val actors = Regex(
            """<a(?=[^>]*class=["'][^"']*\bc-tag\b[^"']*["'])(?=[^>]*href=["'][^"']*/actress/[^"']*["'])[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = outline,
            outline = outline,
            premiered = release,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            runtime = plainTextAfterLabel(html, "収録時間").replace("分", "").digitsOnly(),
            studio = studio,
            publisher = publisherAndStudio.first,
            series = firstLinkTextAfterLabel(html, "シリーズ"),
            directors = listOfNotNull(directorAfterLabel(html).takeIf { it.isNotBlank() }),
            actors = actors,
            genres = tags,
            tags = tags,
            trailer = Regex("""<div[^>]*class=["'][^"']*\bvideo\b[^"']*["'][\s\S]*?<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1).orEmpty(),
            website = detailUrl,
            source = officialUrl.toWebsiteName(),
            thumbUrl = cover,
            posterUrl = posterFromSearch.ifBlank { cover }
        )
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("official 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("official 响应为空：$url")
            val bytes = body.bytes()
            val charset = body.contentType()?.charset() ?: detectCharset(bytes)
            String(bytes, charset)
        }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val charsetName = Regex("""charset=["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
        return runCatching { charsetName?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
    }

    private fun findRealUrl(html: String, baseUrl: String, number: String): SearchResult? {
        val needle = number.uppercase().replace("-", "")
        val anchors = Regex("""<a[^>]+class=["'][^"']*\bimg\b[^"']*\bhover\b[^"']*["'][^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .toList()
        anchors.forEach { match ->
            val href = match.groupValues[1]
            if (href.uppercase().replace("-", "").endsWith(needle)) {
                val poster = Regex("""<img[^>]+(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(match.groupValues[2])
                    ?.groupValues
                    ?.get(1)
                    ?.toAbsoluteUrl(baseUrl)
                    .orEmpty()
                return SearchResult(href.toAbsoluteUrl(baseUrl), poster)
            }
        }

        return Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { it.uppercase().replace("-", "").contains(needle) }
            ?.let { SearchResult(it.toAbsoluteUrl(baseUrl), "") }
    }

    private fun publisherAndStudio(html: String): Pair<String, String> {
        val description = Regex("""<meta[^>]+name=["']description["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtml)
            .orEmpty()
        val fromDescription = Regex("""〖公式〗([^(]+)\(([^)]+)""")
            .find(description)
            ?.let { cleanHtml(it.groupValues[1]) to cleanHtml(it.groupValues[2]) }
        val label = firstLinkTextAfterLabel(html, "レーベル")
        return when {
            label.isNotBlank() -> label to fromDescription?.second.orEmpty()
            fromDescription != null -> fromDescription
            else -> "" to ""
        }
    }

    private fun directorAfterLabel(html: String): String {
        val value = plainTextAfterLabel(html, "監督")
        return if (value.isPlaceholder()) "" else value
    }

    private fun firstLinkTextAfterLabel(html: String, label: String): String =
        linkTextsAfterLabel(html, label).firstOrNull().orEmpty()

    private fun linkTextsAfterLabel(html: String, label: String): List<String> {
        return Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(nextSiblingDivAfterLabel(html, label))
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun plainTextAfterLabel(html: String, label: String): String {
        val area = nextSiblingDivAfterLabel(html, label)
        val paragraph = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
            .find(area)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtml)
            .orEmpty()
        return paragraph.ifBlank { cleanHtml(area) }
    }

    private fun nextSiblingDivAfterLabel(html: String, label: String): String {
        val labelDiv = Regex(
            """<div[^>]*class=["'][^"']*\bth\b[^"']*["'][^>]*>[\s\S]*?${Regex.escape(label)}[\s\S]*?</div>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: Regex(
            """<div[^>]*>[\s\S]*?${Regex.escape(label)}[\s\S]*?</div>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return ""

        val siblingStart = Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html, labelDiv.range.last + 1)
            ?.range
            ?.first
            ?: return ""

        return extractBalancedDiv(html, siblingStart)
    }

    private fun extractBalancedDiv(html: String, start: Int): String {
        val tagRegex = Regex("""</?div\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 0
        tagRegex.findAll(html, start).forEach { match ->
            if (match.value.startsWith("</", ignoreCase = true)) {
                depth -= 1
                if (depth == 0) {
                    return html.substring(start, match.range.last + 1)
                }
            } else {
                depth += 1
            }
        }
        return html.substring(start, minOf(html.length, start + 1200))
    }

    private fun textByRegex(html: String, regex: Regex): String =
        cleanHtml(regex.find(html)?.groupValues?.getOrNull(1).orEmpty())

    private fun String.toAbsoluteUrl(baseUrl: String): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> baseUrl.trimEnd('/') + this
        else -> baseUrl.trimEnd('/') + "/" + this
    }

    private fun String.toWebsiteName(): String {
        val host = substringAfter("://").removePrefix("www.")
        return host.substringBeforeLast(".").substringAfterLast(".")
    }

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun String.isPlaceholder(): Boolean {
        val value = trim()
        if (value.isBlank() || value == "N/A") return true
        return value.all { it in DIRECTOR_PLACEHOLDER_CHARS }
    }

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")
            .trim()

    private fun findOfficialUrl(number: String): String? = OFFICIAL_PREFIXES[prefixOf(number)]

    private fun prefixOf(number: String): String =
        Regex("""([A-Z]+)""").find(number.uppercase())?.value.orEmpty()

    private data class SearchResult(
        val detailUrl: String,
        val posterUrl: String
    )

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val DIRECTOR_PLACEHOLDER_CHARS = setOf('-', 'ー', '―', '～', '~', ' ', '\n')
        val OFFICIAL_PREFIXES = buildOfficialPrefixes()

        fun buildOfficialPrefixes(): Map<String, String> {
            val source = mapOf(
                "https://s1s1s1.com" to "sivr|ssis|ssni|snis|soe|oned|one|onsd|ofje|sps|tksoe",
                "https://moodyz.com" to "mdvr|midv|mide|midd|mibd|mimk|miid|migd|mifd|miae|miad|miaa|mdl|mdj|mdi|mdg|mdf|mde|mdld|mded|mizd|mird|mdjd|rmid|mdid|mdmd|mimu|mdpd|mivd|mdud|mdgd|mdvd|mias|miqd|mint|rmpd|mdrd|tkmide|tkmidd|kmide|tkmigd|mdfd|rmwd|miab",
                "https://www.madonna-av.com" to "juvr|jusd|juq|juy|jux|jul|juk|juc|jukd|oba|jufd|roeb|roe|ure|mdon|jfb|obe|jums",
                "https://www.wanz-factory.com" to "wavr|waaa|bmw|wanz",
                "https://ideapocket.com" to "ipvr|ipx|ipz|iptd|ipsd|idbd|supd|ipit|and|hpd|tkipz|ipzz|cosd|anpd|dan|alad|kipx",
                "https://kirakira-av.com" to "kivr|blk|kibd|kifd|kird|kisd|set",
                "https://www.av-e-body.com" to "ebvr|ebod|mkck|eyan",
                "https://bi-av.com" to "cjvr|cjod|bbi|bib|cjob|beb|bid|bist|bwb",
                "https://premium-beauty.com" to "prvr|pgd|pred|pbd|pjd|prtd|pxd|pid|ptv",
                "https://miman.jp" to "mmvr|mmnd|mmxd|aom",
                "https://tameikegoro.jp" to "mevr|meyd|mbyd|mdyd|mnyd",
                "https://fitch-av.com" to "fcvr|jufe|jufd|jfb|juny|nyb|finh|gcf|nima",
                "https://kawaiikawaii.jp" to "kavr|cawd|kwbd|kawd|kwsr|kwsd|kane",
                "https://befreebe.com" to "bf",
                "https://muku.tv" to "mucd|mudr|mukd|smcd|mukc",
                "https://attackers.net" to "atvr|rbk|rbd|same|shkd|atid|adn|atkd|jbd|sspd|atad|azsd",
                "https://mko-labo.net" to "mvr|mism|emlb",
                "https://dasdas.jp" to "dsvr|dass|dazd|dasd|pla",
                "https://mvg.jp" to "mvsd|mvbd",
                "https://av-opera.jp" to "opvr|opbd|opud",
                "https://oppai-av.com" to "ppvr|pppe|ppbd|pppd|ppsd|ppfd",
                "https://v-av.com" to "vvvd|vicd|vizd|vspd",
                "https://to-satsu.com" to "clvr|stol|club",
                "https://bibian-av.com" to "bbvr|bban",
                "https://honnaka.jp" to "hnvr|hmn|hndb|hnd|krnd|hnky|hnjc|hnse",
                "https://rookie-av.jp" to "rvr|rbb|rki",
                "https://nanpa-japan.jp" to "njvr|nnpj|npjb",
                "https://hajimekikaku.com" to "hjbb|hjmo|avgl",
                "https://hhh-av.com" to "huntb|hunta|hunt|hunbl|royd|tysf",
                "https://www.prestige-av.com" to "abp|mbm|ezd|docp|onez|yrh|abw|abs|chn|mgt|tre|edd|ult|cmi|mbd|dnw|sga|rdd|dcx|evo|rdt|ppt|gets|sim|kil|tus|dtt|gnab|man|mas|tbl|rtp|ctd|fiv|dic|esk|kbi|tem|ama|kfne|trd|har|yrz|srs|mzq|zzr|gzap|tgav|rix|aka|bgn|lxv|afs|goal|giro|cpde|nmp|mct|abc|inu|shl|mbms|pxh|nrs|ftn|prdvr|fst|blo|shs|kum|gsx|ndx|atd|dld|kbh|bcv|raw|soud|job|chs|yok|bsd|fsb|nnn|hyk|sor|hsp|jbs|xnd|mei|day|mmy|kzd|jan|gyan|tdt|tok|dms|fnd|cdc|jcn|pvrbst|sdvr|docvr|fcp|abf"
            )
            return source.flatMap { (site, prefixes) ->
                prefixes.split("|").map { it.uppercase() to site }
            }.toMap()
        }
    }
}
