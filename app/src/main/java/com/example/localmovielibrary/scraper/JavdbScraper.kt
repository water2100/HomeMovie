package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale

class JavdbScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.TheJavDB

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeJavdbNumber(number)
        val url = "$API_URL?q=${URLEncoder.encode(normalized, "UTF-8")}"
        val root = fetchJson(url)
        parseJavdbMovieInfo(normalized, extractJavdbMovieObject(root).toJavdbApiMovie())
    }

    private fun fetchJson(url: String): Any {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("TheJavDB request failed HTTP ${response.code}: $url")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("TheJavDB response is empty: $url")
            JSONTokener(body).nextValue()
        }
    }

    private companion object {
        const val API_URL = "https://api.thejavdb.net/v1/movies"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}

internal data class JavdbApiMovie(
    val universalId: String = "",
    val title: String = "",
    val description: String = "",
    val fullCoverUrl: String = "",
    val frontCoverUrl: String = "",
    val releaseDate: String = "",
    val duration: String = "",
    val sourceUrl: String = "",
    val maker: String = "",
    val label: String = "",
    val series: String = "",
    val actresses: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val genres: List<String> = emptyList()
)

internal fun parseJavdbMovieInfo(requestedNumber: String, movie: JavdbApiMovie): ScrapedMovieInfo {
    val number = movie.universalId.cleanText()
        .ifBlank { normalizeJavdbNumber(requestedNumber) }
    val title = movie.title.cleanText().ifBlank { number }
    val plot = movie.description.cleanText()
    val releaseDate = movie.releaseDate.cleanText()
    val duration = movie.duration.cleanText().digitsOnly()
    val fullCoverUrl = movie.fullCoverUrl.cleanText()
    val frontCoverUrl = movie.frontCoverUrl.cleanText()
    val posterUrl = frontCoverUrl.ifBlank { fullCoverUrl }
    val thumbUrl = fullCoverUrl.ifBlank { posterUrl }
    val genres = movie.genres.cleanStringList()

    return ScrapedMovieInfo(
        number = number,
        title = title,
        originalTitle = title,
        plot = plot,
        outline = plot,
        year = Regex("""\d{4}""").find(releaseDate)?.value.orEmpty(),
        premiered = releaseDate,
        runtime = duration,
        studio = movie.maker.cleanText(),
        publisher = movie.label.cleanText(),
        series = movie.series.cleanText(),
        directors = movie.directors.cleanStringList(),
        actors = movie.actresses.cleanStringList(),
        genres = genres,
        tags = genres,
        website = movie.sourceUrl.cleanText(),
        source = "javdb",
        thumbUrl = thumbUrl,
        posterUrl = posterUrl,
        thumbImageUrls = listOf(thumbUrl).filter { it.isNotBlank() },
        posterImageUrls = listOf(posterUrl).filter { it.isNotBlank() }
    )
}

internal fun extractJavdbMovieObject(root: Any): JSONObject {
    return when (root) {
        is JSONObject -> {
            if (root.has("universal_id")) return root
            root.optJSONObject("data")?.let { return it }
            root.firstMovieFromArray("data")?.let { return it }
            root.firstMovieFromArray("movies")?.let { return it }
            root.firstMovieFromArray("results")?.let { return it }
            error("TheJavDB response does not contain movie data")
        }
        is JSONArray -> root.firstJSONObjectOrNull() ?: error("TheJavDB response list is empty")
        else -> error("Unsupported TheJavDB response type: ${root::class.java.simpleName}")
    }
}

internal fun normalizeJavdbNumber(number: String): String {
    val trimmed = number.trim()
    val match = Regex("""(?i)([a-z0-9]{2,12})[-_ ]+(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""")
        .find(trimmed)
        ?: Regex("""(?i)(\d{0,4}[a-z]{2,12})(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""")
            .find(trimmed)
        ?: return trimmed.uppercase(Locale.ROOT)
    val prefix = match.groupValues[1].uppercase(Locale.ROOT)
    if (!prefix.any { it.isLetter() }) return trimmed.uppercase(Locale.ROOT)
    val digits = match.groupValues[2]
        .trimStart('0')
        .ifBlank { "0" }
        .padStart(3, '0')
    val suffix = match.groupValues[3].uppercase(Locale.ROOT)
    return "$prefix-$digits$suffix"
}

private fun JSONObject.firstMovieFromArray(name: String): JSONObject? =
    optJSONArray(name)?.firstJSONObjectOrNull()

private fun JSONObject.toJavdbApiMovie(): JavdbApiMovie =
    JavdbApiMovie(
        universalId = cleanString("universal_id"),
        title = cleanString("title"),
        description = cleanString("description"),
        fullCoverUrl = cleanString("fullcover_url"),
        frontCoverUrl = cleanString("frontcover_url"),
        releaseDate = cleanString("release_date"),
        duration = cleanString("duration"),
        sourceUrl = cleanString("source_url"),
        maker = cleanString("maker"),
        label = cleanString("label"),
        series = cleanString("series"),
        actresses = optJSONArray("actresses").toCleanStringList(),
        directors = optJSONArray("directors").toCleanStringList(),
        genres = optJSONArray("genres").toCleanStringList()
    )

private fun JSONArray?.firstJSONObjectOrNull(): JSONObject? {
    if (this == null) return null
    for (index in 0 until length()) {
        val item = opt(index)
        if (item is JSONObject) return item
    }
    return null
}

private fun JSONArray?.toCleanStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index -> optString(index).cleanText().takeIf { it.isNotBlank() } }
        .distinct()
}

private fun List<String>.cleanStringList(): List<String> =
    mapNotNull { it.cleanText().takeIf { value -> value.isNotBlank() } }
        .distinct()

private fun JSONObject.cleanString(name: String): String =
    optString(name).cleanText()

private fun String.digitsOnly(): String =
    Regex("""\d+""").find(this)?.value.orEmpty()

private fun String.cleanText(): String =
    replace("&amp;", "&")
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
