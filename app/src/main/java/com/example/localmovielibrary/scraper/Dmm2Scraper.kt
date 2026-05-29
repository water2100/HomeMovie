package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class Dmm2Scraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Dmm2

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        val keyword = normalizeNumberForSearch(normalized)
        val searchJson = fetchSearch(keyword)
        val contents = searchJson
            .optJSONObject("data")
            ?.optJSONObject("legacySearchPPV")
            ?.optJSONObject("result")
            ?.optJSONArray("contents")
            ?: JSONArray()
        if (contents.length() == 0) error("DMM2 没有搜索到结果：$normalized / $keyword")

        val selected = selectBestSearchResult(contents, keyword)
        val contentId = selected.optString("id").trim()
        if (contentId.isBlank()) error("DMM2 搜索结果没有 content id")

        val detailJson = fetchDetail(contentId)
        val info = parseMovieInfo(normalized, selected, detailJson)
        if (info.title.isBlank()) error("DMM2 没有解析到标题：$normalized")
        info
    }

    private fun fetchSearch(keyword: String): JSONObject {
        val variables = JSONObject()
            .put("limit", 20)
            .put("offset", 0)
            .put("floor", "AV")
            .put("sort", "SALES_RANK_SCORE")
            .put("queryWord", keyword)
            .put("filter", JSONObject())
            .put("facetLimit", 100)
            .put("excludeUndelivered", true)
        val payload = JSONObject()
            .put("operationName", "AvSearch")
            .put("query", SEARCH_QUERY)
            .put("variables", variables)
        return postGraphql(payload, "https://video.dmm.co.jp/av/list/?key=$keyword")
    }

    private fun fetchDetail(contentId: String): JSONObject {
        val payload = JSONObject()
            .put("operationName", "Test")
            .put("query", DETAIL_QUERY)
            .put("variables", JSONObject().put("id", contentId))
        return postGraphql(payload, "https://video.dmm.co.jp/av/content/?id=$contentId")
    }

    private fun postGraphql(payload: JSONObject, referer: String): JSONObject {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            runCatching {
                val request = Request.Builder()
                    .url(GRAPHQL_URL)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://video.dmm.co.jp")
                    .header("Referer", referer)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("DMM2 GraphQL 请求失败 HTTP ${response.code}: ${body.take(300)}")
                    }
                    return JSONObject(body)
                }
            }.onFailure { error ->
                lastError = error
                if (attempt < 2) Thread.sleep(1500)
            }
        }
        throw RuntimeException("DMM2 GraphQL 请求连续失败：${lastError?.message ?: lastError?.javaClass?.simpleName}")
    }

    private fun selectBestSearchResult(contents: JSONArray, keyword: String): JSONObject {
        val items = (0 until contents.length()).mapNotNull { contents.optJSONObject(it) }
        return items.maxByOrNull { scoreSearchItem(it, keyword.lowercase(Locale.ROOT)) }
            ?: error("DMM2 搜索接口没有返回内容")
    }

    private fun scoreSearchItem(item: JSONObject, keyword: String): Int {
        val contentId = item.optString("id").lowercase(Locale.ROOT)
        val title = item.optString("title").lowercase(Locale.ROOT)
        var score = 0
        if (contentId == keyword) score += 200
        if (keyword in contentId) score += 150
        val relaxed = keyword.replace(Regex("""0+(\d+)$"""), "$1")
        if (relaxed.isNotBlank() && relaxed in contentId) score += 30
        if (keyword in title) score += 20
        listOf("tp", "tapestry", "tokuten", "goods", "set", "limited").forEach { bad ->
            if (bad in contentId) score -= 30
        }
        return score
    }

    private fun parseMovieInfo(number: String, searchItem: JSONObject, detailJson: JSONObject): ScrapedMovieInfo {
        val data = detailJson.optJSONObject("data") ?: JSONObject()
        val ppv = data.optJSONObject("ppvContent") ?: error("DMM2 详情接口没有返回 ppvContent")
        val review = data.optJSONObject("reviewSummary")

        val contentId = ppv.optString("id").ifBlank { searchItem.optString("id") }
        val title = ppv.optString("title").ifBlank { searchItem.optString("title") }.cleanText()
        val packageImage = ppv.optJSONObject("packageImage") ?: JSONObject()
        val thumb = packageImage.optString("largeUrl").ifBlank { packageImage.optString("mediumUrl") }.cleanText()
        val poster = packageImage.optString("mediumUrl")
            .ifBlank { buildPosterUrl(thumb) }
            .let { if (it == thumb) buildPosterUrl(thumb) else it }
            .cleanText()

        val release = parseChinaDate(searchItem.optString("deliveryStartAt"))
        val tags = ppv.optJSONArray("genres").namesFromObjects()
        val actors = ppv.optJSONArray("actresses").namesFromObjects()
        val actorImageUrls = ppv.optJSONArray("actresses").imageUrlsByName()
        val maker = ppv.optJSONObject("maker")?.optString("name").orEmpty().cleanText()
        val label = ppv.optJSONObject("label")?.optString("name").orEmpty().cleanText()
        val series = ppv.optJSONObject("series")?.optString("name").orEmpty().cleanText()
        val rating = review?.optString("average").orEmpty().cleanText()
            .ifBlank { searchItem.optJSONObject("review")?.optString("average").orEmpty().cleanText() }
        val sampleMovie = searchItem.optJSONObject("sampleMovie") ?: JSONObject()
        val trailer = sampleMovie.optString("mp4Url").ifBlank { sampleMovie.optString("hlsUrl") }.cleanText()
        val plot = ppv.optString("description").cleanText()
            .ifBlank {
                ppv.optJSONArray("announcements")
                    ?.let { announcements ->
                        (0 until announcements.length())
                            .mapNotNull { announcements.optJSONObject(it)?.optString("body")?.cleanText() }
                            .firstOrNull { it.isNotBlank() }
                    }
                    .orEmpty()
            }

        return ScrapedMovieInfo(
            number = number.uppercase(Locale.ROOT),
            title = title.ifBlank { number.uppercase(Locale.ROOT) },
            originalTitle = title.ifBlank { number.uppercase(Locale.ROOT) },
            plot = plot,
            outline = plot,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = "",
            studio = maker,
            publisher = label,
            series = series,
            directors = emptyList(),
            actors = actors,
            actorImageUrls = actorImageUrls,
            genres = tags,
            tags = tags,
            rating = rating,
            trailer = trailer,
            website = buildVideoContentUrl(contentId),
            source = "dmm2",
            thumbUrl = thumb,
            posterUrl = poster
        )
    }

    private fun JSONArray?.namesFromObjects(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optJSONObject(it)?.optString("name")?.cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun JSONArray?.imageUrlsByName(): Map<String, String> {
        if (this == null) return emptyMap()
        return (0 until length())
            .mapNotNull { index ->
                val actor = optJSONObject(index) ?: return@mapNotNull null
                val name = actor.optString("name").cleanText()
                val imageUrl = actor.optString("imageUrl").cleanText()
                if (name.isBlank() || imageUrl.isBlank()) null else name to imageUrl
            }
            .distinctBy { it.first }
            .toMap()
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,10})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    }

    private fun normalizeNumberForSearch(number: String): String {
        val match = Regex("""(?i)^([a-z]+)-?(\d+)$""").find(number.trim().replace("_", "-"))
            ?: return number.lowercase(Locale.ROOT).replace("-", "")
        return match.groupValues[1].lowercase(Locale.ROOT) + match.groupValues[2].toInt().toString().padStart(5, '0')
    }

    private fun buildPosterUrl(thumbUrl: String): String =
        thumbUrl.replace(Regex("""pl(\.(jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE), "ps\$1")

    private fun buildVideoContentUrl(contentId: String): String =
        "https://video.dmm.co.jp/av/content/?id=$contentId&i3_ref=search&i3_ord=1&i3_pst=1&dmmref=video_search"

    private fun parseChinaDate(value: String): String {
        val source = value.cleanText()
        if (source.isBlank()) return ""
        val date = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(source)?.value ?: return ""
        if (!source.contains("T00:00:00+09:00", ignoreCase = true)) return date
        return runCatching {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            calendar.time = formatter.parse(date) ?: return@runCatching date
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            formatter.format(calendar.time)
        }.getOrDefault(date)
    }

    private fun String.cleanText(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")
            .trim()

    private companion object {
        const val GRAPHQL_URL = "https://api.video.dmm.co.jp/graphql"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val SEARCH_QUERY = """
query AvSearch(${'$'}limit: Int!, ${'$'}offset: Int, ${'$'}floor: PPVFloor, ${'$'}sort: ContentSearchPPVSort!, ${'$'}queryWord: String, ${'$'}filter: ContentSearchPPVFilterInput, ${'$'}facetLimit: Int!, ${'$'}excludeUndelivered: Boolean!) {
  legacySearchPPV(limit: ${'$'}limit, offset: ${'$'}offset, floor: ${'$'}floor, sort: ${'$'}sort, queryWord: ${'$'}queryWord, filter: ${'$'}filter, facetLimit: ${'$'}facetLimit, includeExplicit: true, excludeUndelivered: ${'$'}excludeUndelivered) {
    result {
      contents {
        id
        title
        floor
        contentType
        packageImage { mediumUrl largeUrl }
        sampleImages { number largeUrl }
        sampleMovie { hlsUrl mp4Url vrUrl }
        releaseStatus
        review { average count }
        deliveryStartAt
        actresses { id name }
        maker { id name }
      }
      pageInfo { totalCount limit offset hasNext }
    }
  }
}
"""

        const val DETAIL_QUERY = """
query Test(${'$'}id: ID!) {
    ppvContent(id: ${'$'}id) {
    id
    title
    description
    notices
    announcements { body }
    floor
    contentType
    releaseStatus
    isAllowForeign
    packageImage { mediumUrl largeUrl }
    sampleImages { number imageUrl largeImageUrl }
    maker { id name }
    label { id name }
    series { id name }
    genres { id name }
    actresses { id name imageUrl }
  }
  reviewSummary(contentId: ${'$'}id) {
    average
    total
    withCommentTotal
  }
}
"""
    }
}
