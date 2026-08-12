package com.example.localmovielibrary.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.example.localmovielibrary.util.NumberRecognitionRules
import java.util.Locale
import java.util.concurrent.TimeUnit

class RemoteScrapeConfigRepository(
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun getMgstageNumberPrefixes(): Set<String> {
        loadCachedNumberRecognitionRules()
        return settingsRepository.getMergedMgstageNumberPrefixes()
    }

    fun loadCachedNumberRecognitionRules(): Set<String> {
        NumberRecognitionRules.updateIgnoredSuffixes(settingsRepository.getCachedNumberRecognitionIgnoredSuffixes())
        NumberRecognitionRules.updatePartMarkers(settingsRepository.getCachedNumberRecognitionPartMarkers())
        NumberRecognitionRules.updateNumericPrefixAliases(settingsRepository.getMergedMgstageSearchPrefixAliases())
        return settingsRepository.getCachedNumberRecognitionIgnoredSuffixes()
    }

    suspend fun syncRemoteNumberRecognitionRules(): Set<String> = withContext(ioDispatcher) {
        val configUrl = settingsRepository.getRemoteScrapeConfigUrl()
        if (configUrl.isBlank()) error("远程刮削规则地址为空")

        val now = System.currentTimeMillis()
        val request = Request.Builder()
            .url(updateRequestUrl(configUrl))
            .header("Accept", "application/json")
            .header("User-Agent", "HomeMovie/remote-scrape-config")
            .build()
        val body = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("远程刮削配置请求失败 HTTP ${response.code}")
            if (text.isBlank()) error("远程刮削配置为空")
            text
        }
        val rules = parseMgstageRules(body)
        settingsRepository.saveRemoteScrapeConfigLastFetchMillis(now)
        settingsRepository.saveCachedMgstageNumberPrefixes(rules.prefixes)
        settingsRepository.saveCachedMgstageSearchPrefixAliases(rules.searchPrefixAliases)
        settingsRepository.saveCachedNumberRecognitionIgnoredSuffixes(rules.numberRecognitionIgnoredSuffixes)
        settingsRepository.saveCachedNumberRecognitionPartMarkers(rules.numberRecognitionPartMarkers)
        loadCachedNumberRecognitionRules()
        settingsRepository.getMergedMgstageNumberPrefixes()
    }

    fun getCachedMgstageSearchPrefixAliases(): Map<String, String> =
        settingsRepository.getMergedMgstageSearchPrefixAliases()

    private fun parseMgstageRules(jsonText: String): MgstageRules {
        val json = JSONObject(jsonText)
        val mgstage = json.optJSONObject("mgstage")
        if (mgstage != null && !mgstage.optBoolean("enabled", true)) {
            return MgstageRules(
                prefixes = emptySet(),
                searchPrefixAliases = emptyMap(),
                numberRecognitionIgnoredSuffixes = parseNumberRecognitionIgnoredSuffixes(json),
                numberRecognitionPartMarkers = parseNumberRecognitionPartMarkers(json)
            )
        }
        val array = mgstage?.optJSONArray("prefixes")
            ?: json.optJSONArray("mgstagePrefixes")
            ?: json.optJSONArray("mgstage_number_prefixes")
            ?: JSONArray()
        val legacySearchPrefixAliases = (
            mgstage?.optJSONObject("searchPrefixAliases")
                ?: mgstage?.optJSONObject("numberPrefixAliases")
                ?: json.optJSONObject("mgstageSearchPrefixAliases")
                ?: json.optJSONObject("mgstage_search_prefix_aliases")
                ?: JSONObject()
            ).toPrefixAliasMap()
        val prefixNumberMappings = (
            mgstage?.optJSONObject("prefixNumberMappings")
                ?: mgstage?.optJSONObject("prefix_number_mappings")
                ?: json.optJSONObject("mgstagePrefixNumberMappings")
                ?: json.optJSONObject("mgstage_prefix_number_mappings")
                ?: JSONObject()
            ).toPrefixNumberAliasMap()
        val searchPrefixAliases = legacySearchPrefixAliases + prefixNumberMappings
        val prefixes = (array.toPrefixSet() + searchPrefixAliases.keys)
            .filterNot { it == LEGACY_CUTE_PREFIX || it.firstOrNull()?.isDigit() == true }
            .toSet()
        return MgstageRules(
            prefixes = prefixes,
            searchPrefixAliases = searchPrefixAliases,
            numberRecognitionIgnoredSuffixes = parseNumberRecognitionIgnoredSuffixes(json),
            numberRecognitionPartMarkers = parseNumberRecognitionPartMarkers(json)
        )
    }

    private fun parseNumberRecognitionIgnoredSuffixes(json: JSONObject): Set<String> {
        val numberRecognition = json.optJSONObject("numberRecognition")
            ?: json.optJSONObject("number_recognition")
        val array = numberRecognition?.optJSONArray("ignoredSuffixes")
            ?: numberRecognition?.optJSONArray("ignored_suffixes")
            ?: json.optJSONArray("numberRecognitionIgnoredSuffixes")
            ?: json.optJSONArray("number_recognition_ignored_suffixes")
            ?: JSONArray()
        return NumberRecognitionRules.normalizeIgnoredSuffixes(
            (0 until array.length()).map { array.optString(it) }
        )
    }

    private fun parseNumberRecognitionPartMarkers(json: JSONObject): Set<String> {
        val numberRecognition = json.optJSONObject("numberRecognition")
            ?: json.optJSONObject("number_recognition")
        val array = numberRecognition?.optJSONArray("partMarkers")
            ?: numberRecognition?.optJSONArray("part_markers")
            ?: numberRecognition?.optJSONArray("segmentMarkers")
            ?: numberRecognition?.optJSONArray("segment_markers")
            ?: json.optJSONArray("numberRecognitionPartMarkers")
            ?: json.optJSONArray("number_recognition_part_markers")
            ?: JSONArray()
        return NumberRecognitionRules.normalizePartMarkers(
            (0 until array.length()).map { array.optString(it) }
        )
    }

    private fun JSONArray.toPrefixSet(): Set<String> =
        (0 until length())
            .mapNotNull { optString(it).normalizedNumberPrefixOrNull() }
            .toSet()

    private fun JSONObject.toPrefixAliasMap(): Map<String, String> =
        keys().asSequence()
            .mapNotNull { key ->
                val normalizedKey = key.normalizedNumberPrefixOrNull() ?: return@mapNotNull null
                val normalizedValue = optString(key).normalizedNumberPrefixOrNull() ?: return@mapNotNull null
                val numericPrefix = normalizedValue.takeWhile(Char::isDigit)
                val valuePrefix = normalizedValue.drop(numericPrefix.length).normalizedNumberPrefixOrNull()
                val canonicalPrefix = if (numericPrefix.isNotEmpty() && valuePrefix != null) valuePrefix else normalizedKey
                canonicalPrefix to if (numericPrefix.isEmpty()) canonicalPrefix else "$numericPrefix$canonicalPrefix"
            }
            .toMap()

    private fun JSONObject.toPrefixNumberAliasMap(): Map<String, String> =
        keys().asSequence()
            .mapNotNull { key ->
                val prefix = key.normalizedNumberPrefixOrNull() ?: return@mapNotNull null
                val numericPrefix = optString(key).filter(Char::isDigit)
                prefix to "$numericPrefix$prefix"
            }
            .toMap()

    private fun updateRequestUrl(url: String): String {
        val normalizedUrl = url.trim()
        val proxyBaseUrl = settingsRepository.getUpdateProxyBaseUrl()
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
            ?: return normalizedUrl
        if (!normalizedUrl.isGithubUrl() || normalizedUrl.startsWith("$proxyBaseUrl/", ignoreCase = true)) {
            return normalizedUrl
        }
        return "$proxyBaseUrl/$normalizedUrl"
    }

    private fun String.isGithubUrl(): Boolean =
        startsWith("https://github.com/", ignoreCase = true) ||
            startsWith("http://github.com/", ignoreCase = true)

    private fun String.normalizedNumberPrefixOrNull(): String? =
        trim()
            .uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .takeIf { value -> value.isNotBlank() && value.any { it.isLetter() } }

    private companion object {
        const val LEGACY_CUTE_PREFIX = "CUTE"
    }
}

data class MgstageRules(
    val prefixes: Set<String>,
    val searchPrefixAliases: Map<String, String>,
    val numberRecognitionIgnoredSuffixes: Set<String>,
    val numberRecognitionPartMarkers: Set<String>
)
