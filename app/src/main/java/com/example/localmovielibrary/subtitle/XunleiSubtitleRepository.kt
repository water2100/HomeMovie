package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.diagnostics.RuntimeErrorLog
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class XunleiSubtitleRepository(
    context: Context,
    settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val errorLog = RuntimeErrorLog(appContext)
    private val fileStore = LocalSubtitleStore(appContext, settingsRepository)

    suspend fun search(number: String, videoDurationMs: Long): List<SubtitleSearchResult> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeXunleiSubtitleNumber(number)
            if (normalized.isBlank()) return@withContext emptyList()
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", normalized)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
            errorLog.append(
                event = "xunlei.subtitle.search.request",
                details = mapOf("url" to url.toString(), "number" to normalized)
            )
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("迅雷字幕搜索失败：HTTP ${response.code}")
                if (body.isBlank()) error("迅雷字幕搜索结果为空")
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 0) {
                    error("迅雷字幕搜索失败：${json.optString("result", "未知错误")}")
                }
                val data = json.optJSONArray("data") ?: return@withContext emptyList()
                buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val result = item.toSubtitleResult() ?: continue
                        if (result.isCloseTo(videoDurationMs)) add(result)
                    }
                }.distinctBy { "${it.cid}:${it.ext}:${it.durationMs}" }
            }
        }

    suspend fun download(
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = withContext(Dispatchers.IO) {
        val url = result.signature.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: error("迅雷字幕下载地址为空")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()
        errorLog.append(
            event = "xunlei.subtitle.download.request",
            details = mapOf("url" to url, "name" to result.name, "ext" to result.ext)
        )
        val bytes = client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) error("迅雷字幕下载失败：HTTP ${response.code}")
            if (bytes.isEmpty()) error("迅雷字幕下载结果为空")
            bytes
        }
        fileStore.saveSubtitleBytes(
            videoUri = videoUri,
            fileName = fileName,
            result = result,
            bytes = bytes,
            storageSourceUri = storageSourceUri
        )
    }

    private fun JSONObject.toSubtitleResult(): SubtitleSearchResult? {
        val url = optString("url").trim()
        val cid = optString("cid").ifBlank { optString("gcid") }.trim()
        val ext = optString("ext").ifBlank { url.substringAfterLast('.', "srt") }
            .lowercase(Locale.ROOT)
            .takeIf { it in SUPPORTED_EXTENSIONS }
            ?: return null
        val name = optString("name").ifBlank { "$cid.$ext" }.trim()
        if (url.isBlank() || cid.isBlank() || name.isBlank()) return null
        val rawDuration = optLong("duration", 0L)
        val durationMs = when {
            rawDuration <= 0L -> null
            rawDuration < 10_000L -> rawDuration * 1_000L
            rawDuration < 24L * 60L * 60L * 1_000L -> rawDuration
            else -> null
        }
        val languages = optJSONArray("languages")
        val language = buildList {
            if (languages != null) {
                for (index in 0 until languages.length()) {
                    languages.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.joinToString("/")
        return SubtitleSearchResult(
            cid = cid,
            ext = ext,
            name = name,
            durationMs = durationMs,
            language = language,
            extraName = optString("extra_name"),
            timestamp = 0L,
            signature = url,
            provider = SubtitleSearchProvider.Xunlei
        )
    }

    private fun SubtitleSearchResult.isCloseTo(videoDurationMs: Long): Boolean {
        val subtitleDuration = durationMs ?: return true
        if (videoDurationMs <= 0L) return true
        return abs(subtitleDuration - videoDurationMs) <= MAX_DURATION_DIFF_MS
    }

    companion object {
        private const val BASE_URL = "http://api-shoulei-ssl.xunlei.com/oracle/subtitle"
        private const val MAX_DURATION_DIFF_MS = 10L * 60L * 1_000L
        private val SUPPORTED_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    }
}

fun normalizeXunleiSubtitleNumber(number: String): String {
    val value = number.trim().uppercase(Locale.ROOT).replace("_", "-")
    val match = Regex("""^([A-Z]+)-?(\d+)$""").matchEntire(value) ?: return value
    val prefix = match.groupValues[1]
    val digits = match.groupValues[2]
    return "$prefix-${digits.padStart(3, '0')}"
}
