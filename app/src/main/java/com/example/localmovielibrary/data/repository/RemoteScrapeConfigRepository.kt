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
    suspend fun getMgstageNumberPrefixes(forceRefresh: Boolean = false): Set<String> = withContext(ioDispatcher) {
        val custom = settingsRepository.getCustomMgstageNumberPrefixes()
        val cachedRemote = settingsRepository.getCachedMgstageNumberPrefixes()
        val fallback = cachedRemote + custom
        val configUrl = settingsRepository.getRemoteScrapeConfigUrl()
        if (configUrl.isBlank()) return@withContext fallback

        val now = System.currentTimeMillis()
        val shouldRefresh = forceRefresh ||
            now - settingsRepository.getRemoteScrapeConfigLastFetchMillis() >= REFRESH_INTERVAL_MS
        if (!shouldRefresh) return@withContext fallback

        runCatching {
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
            parseMgstageRules(body)
        }.onSuccess { rules ->
            settingsRepository.saveRemoteScrapeConfigLastFetchMillis(now)
            settingsRepository.saveCachedMgstageNumberPrefixes(rules.prefixes)
            settingsRepository.saveCachedMgstageSearchPrefixAliases(rules.searchPrefixAliases)
            settingsRepository.saveCachedNumberRecognitionIgnoredSuffixes(rules.numberRecognitionIgnoredSuffixes)
            return@withContext rules.prefixes + custom
        }.onFailure {
            settingsRepository.saveRemoteScrapeConfigLastFetchMillis(now)
        }

        fallback
    }

    fun getCachedMgstageSearchPrefixAliases(): Map<String, String> =
        settingsRepository.getCachedMgstageSearchPrefixAliases()

    suspend fun refreshNumberRecognitionRules(forceRefresh: Boolean = false): Set<String> {
        getMgstageNumberPrefixes(forceRefresh = forceRefresh)
        return settingsRepository.getCachedNumberRecognitionIgnoredSuffixes()
    }

    private fun parseMgstageRules(jsonText: String): MgstageRules {
        val json = JSONObject(jsonText)
        val mgstage = json.optJSONObject("mgstage")
        if (mgstage != null && !mgstage.optBoolean("enabled", true)) {
            return MgstageRules(
                prefixes = emptySet(),
                searchPrefixAliases = emptyMap(),
                numberRecognitionIgnoredSuffixes = parseNumberRecognitionIgnoredSuffixes(json)
            )
        }
        val array = mgstage?.optJSONArray("prefixes")
            ?: json.optJSONArray("mgstagePrefixes")
            ?: json.optJSONArray("mgstage_number_prefixes")
            ?: JSONArray()
        val searchPrefixAliases = (
            mgstage?.optJSONObject("searchPrefixAliases")
                ?: mgstage?.optJSONObject("numberPrefixAliases")
                ?: json.optJSONObject("mgstageSearchPrefixAliases")
                ?: json.optJSONObject("mgstage_search_prefix_aliases")
                ?: JSONObject()
            ).toPrefixAliasMap()
        val prefixes = array.toPrefixSet() + searchPrefixAliases.keys + searchPrefixAliases.values
        return MgstageRules(
            prefixes = prefixes,
            searchPrefixAliases = searchPrefixAliases,
            numberRecognitionIgnoredSuffixes = parseNumberRecognitionIgnoredSuffixes(json)
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

    private fun JSONArray.toPrefixSet(): Set<String> =
        (0 until length())
            .mapNotNull { optString(it).normalizedNumberPrefixOrNull() }
            .toSet()

    private fun JSONObject.toPrefixAliasMap(): Map<String, String> =
        keys().asSequence()
            .mapNotNull { key ->
                val normalizedKey = key.normalizedNumberPrefixOrNull() ?: return@mapNotNull null
                val normalizedValue = optString(key).normalizedNumberPrefixOrNull() ?: return@mapNotNull null
                normalizedKey to normalizedValue
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
        const val REFRESH_INTERVAL_MS = 10L * 60L * 1000L
    }
}

data class MgstageRules(
    val prefixes: Set<String>,
    val searchPrefixAliases: Map<String, String>,
    val numberRecognitionIgnoredSuffixes: Set<String>
)
