package com.example.localmovielibrary.scraper

import com.example.localmovielibrary.data.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CustomJsonScraper(
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.CustomJson

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val config = settingsRepository.getCustomJsonScrapeConfig()
        val (url, body) = fetchBody(config, number)
        val json = JSONObject(body)
        val root = json.select(config.resultPath).firstOrNull() ?: json
        val resultNumber = root.firstText(config.numberPath).ifBlank { number }
        val title = root.firstText(config.titlePath).ifBlank { resultNumber }
        val plot = root.firstText(config.plotPath)
        val thumbUrl = root.firstText(config.thumbPath)
        val posterUrl = root.firstText(config.posterPath)
        val fanartUrl = root.firstText(config.fanartPath)
        ScrapedMovieInfo(
            number = resultNumber,
            title = title,
            originalTitle = root.firstText(config.originalTitlePath).ifBlank { title },
            plot = plot,
            outline = plot,
            premiered = root.firstText(config.premieredPath).normalizedCustomJsonDate(),
            runtime = root.firstText(config.runtimePath),
            studio = root.firstText(config.studioPath),
            series = root.firstText(config.seriesPath),
            actors = root.textList(config.actorsPath),
            genres = root.textList(config.genresPath),
            tags = root.textList(config.tagsPath),
            rating = root.firstText(config.ratingPath),
            website = url,
            source = config.name.ifBlank { "自定义 JSON" },
            thumbUrl = thumbUrl.ifBlank { fanartUrl },
            posterUrl = posterUrl,
            fanartUrl = fanartUrl,
            thumbImageUrls = listOf(thumbUrl, fanartUrl).filter { it.isNotBlank() },
            posterImageUrls = listOf(posterUrl).filter { it.isNotBlank() }
        )
    }

    suspend fun previewPathCandidates(number: String): List<CustomJsonPathCandidate> = withContext(ioDispatcher) {
        val config = settingsRepository.getCustomJsonScrapeConfig()
        val (_, body) = fetchBody(config, number)
        val json = JSONObject(body)
        val root = json.select(config.resultPath).firstOrNull() ?: json
        root.flattenJsonPaths("$").take(MAX_PATH_CANDIDATES)
    }

    private fun fetchBody(config: CustomJsonScrapeConfig, number: String): Pair<String, String> {
        if (!config.enabled) error("自定义 JSON 刮削源未启用")
        val url = config.urlTemplate.replace("{number}", number)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("自定义 JSON 刮削地址必须以 http:// 或 https:// 开头")
        }
        val request = Request.Builder().url(url).get().build()
        val body = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("自定义 JSON 刮削失败：HTTP " + response.code)
            if (body.isBlank()) error("自定义 JSON 刮削结果为空")
            body
        }
        return url to body
    }
}

data class CustomJsonPathCandidate(
    val path: String,
    val valuePreview: String
)

data class CustomJsonScrapeConfig(
    val enabled: Boolean = false,
    val name: String = "自定义 JSON",
    val urlTemplate: String = "",
    val sampleNumber: String = "SSIS-115",
    val resultPath: String = "$",
    val numberPath: String = "$.number",
    val titlePath: String = "$.title",
    val originalTitlePath: String = "",
    val plotPath: String = "$.plot",
    val premieredPath: String = "$.premiered",
    val runtimePath: String = "$.runtime",
    val studioPath: String = "$.studio",
    val seriesPath: String = "$.series",
    val actorsPath: String = "$.actors",
    val genresPath: String = "$.genres",
    val tagsPath: String = "$.tags",
    val ratingPath: String = "$.rating",
    val posterPath: String = "$.poster",
    val thumbPath: String = "$.thumb",
    val fanartPath: String = "$.fanart"
)

private fun Any.firstText(path: String): String =
    select(path).firstOrNull()?.jsonText().orEmpty()

private fun Any.textList(path: String): List<String> =
    select(path).flatMap { value ->
        when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { index -> value.opt(index)?.jsonText() }
            else -> value.jsonText().split(',', '/', '、').map { it.trim() }
        }
    }.filter { it.isNotBlank() }.distinct()

private fun Any.select(path: String): List<Any> {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return emptyList()
    if (trimmed == "$") return listOf(this)
    val tokens = trimmed.pathTokens()
    return tokens.fold(listOf(this)) { current, token ->
        current.flatMap { value -> value.selectToken(token) }
    }
}

private fun String.pathTokens(): List<String> =
    removePrefix("$.")
        .removePrefix("$")
        .trimStart('.')
        .split('.')
        .filter { it.isNotBlank() }

private fun Any.selectToken(token: String): List<Any> {
    val name = token.substringBefore('[')
    val selector = token.substringAfter('[', "").substringBefore(']', "")
    val base = when (this) {
        is JSONObject -> opt(name)
        is JSONArray -> name.toIntOrNull()?.let { opt(it) }
        else -> null
    } ?: return emptyList()
    return when {
        selector == "*" && base is JSONArray -> (0 until base.length()).mapNotNull { base.opt(it) }
        selector.isNotBlank() && base is JSONArray -> selector.toIntOrNull()?.let { listOfNotNull(base.opt(it)) }.orEmpty()
        else -> listOf(base)
    }
}

private fun Any.jsonText(): String = when (this) {
    is JSONObject -> optString("name").ifBlank { toString() }
    is JSONArray -> (0 until length()).mapNotNull { opt(it)?.jsonText() }.joinToString(", ")
    JSONObject.NULL -> ""
    else -> toString().trim()
}

private fun Any.flattenJsonPaths(path: String): List<CustomJsonPathCandidate> = when (this) {
    is JSONObject -> keys().asSequence()
        .toList()
        .sorted()
        .flatMap { key -> opt(key)?.flattenJsonPaths("$path.$key").orEmpty() }
    is JSONArray -> flattenArrayPaths(path)
    JSONObject.NULL -> emptyList()
    else -> listOf(CustomJsonPathCandidate(path, jsonText().previewText()))
}

private fun JSONArray.flattenArrayPaths(path: String): List<CustomJsonPathCandidate> {
    if (length() == 0) return emptyList()
    val values = (0 until length()).mapNotNull { index -> opt(index) }
    if (values.none { it is JSONObject || it is JSONArray }) {
        return listOf(CustomJsonPathCandidate(path, values.joinToString(", ") { it.jsonText() }.previewText()))
    }
    return values
        .flatMap { value -> value.flattenJsonPaths("$path[*]") }
        .groupBy { it.path }
        .map { (candidatePath, candidates) ->
            CustomJsonPathCandidate(
                path = candidatePath,
                valuePreview = candidates.map { it.valuePreview }.filter { it.isNotBlank() }.distinct().joinToString(", ").previewText()
            )
        }
}

private fun String.previewText(maxLength: Int = 96): String {
    val singleLine = replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1) + "…"
}

private fun String.normalizedCustomJsonDate(): String {
    val value = trim()
    if (value.isBlank()) return ""
    val output = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Hong_Kong")
    }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mmX"
    )
    patterns.forEach { pattern ->
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
        }.getOrNull()
        if (parsed != null) return output.format(parsed)
    }
    return Regex("""\d{4}-\d{2}-\d{2}""").find(value)?.value ?: value
}

private const val MAX_PATH_CANDIDATES = 240
