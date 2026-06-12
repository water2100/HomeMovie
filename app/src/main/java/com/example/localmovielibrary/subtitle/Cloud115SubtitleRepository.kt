package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.diagnostics.RuntimeErrorLog
import com.example.localmovielibrary.util.normalizeMovieNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class Cloud115SubtitleRepository(
    context: Context,
    settingsRepository: AppSettingsRepository,
    private val cloud115Client: Cloud115Client
) {
    private val appContext = context.applicationContext
    private val errorLog = RuntimeErrorLog(appContext)
    private val fileStore = JavzimuSubtitleRepository(appContext, settingsRepository)

    suspend fun search(number: String): List<JavzimuSubtitleResult> = withContext(Dispatchers.IO) {
        val normalized = normalizeCloud115SubtitleNumber(number)
        if (normalized.isBlank()) return@withContext emptyList()
        errorLog.append(
            event = "cloud115.subtitle.search.request",
            details = mapOf("number" to normalized)
        )
        cloud115Client.searchFiles(
            keyword = normalized,
            limit = SEARCH_LIMIT,
            offset = 0,
            type = SEARCH_TYPE_ALL
        )
            .mapNotNull { it.toSubtitleResult(normalized) }
            .distinctBy { "${it.signature}:${it.name.lowercase(Locale.ROOT)}" }
    }

    suspend fun download(
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = withContext(Dispatchers.IO) {
        val pickcode = result.signature.takeIf { it.isNotBlank() }
            ?: error("网盘字幕缺少 pickcode")
        errorLog.append(
            event = "cloud115.subtitle.download.request",
            details = mapOf(
                "name" to result.name,
                "ext" to result.ext,
                "pickcode" to pickcode
            )
        )
        val directUrl = cloud115Client.fetchDirectUrl(pickcode)
        val bytes = cloud115Client.downloadBytes(directUrl)
        if (bytes.isEmpty()) error("网盘字幕下载结果为空")
        fileStore.saveSubtitleBytes(
            videoUri = videoUri,
            fileName = fileName,
            result = result,
            bytes = bytes,
            storageSourceUri = storageSourceUri
        )
    }

    private fun Cloud115FileItem.toSubtitleResult(number: String): JavzimuSubtitleResult? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in SUBTITLE_EXTENSIONS }
            ?: return null
        val pickcode = pickcode?.takeIf { it.isNotBlank() } ?: return null
        if (!name.matchesSubtitleNumber(number)) return null
        return JavzimuSubtitleResult(
            cid = fid?.toString() ?: pickcode,
            ext = ext,
            name = name,
            durationMs = null,
            language = "",
            extraName = size?.takeIf { it > 0L }?.let(::formatBytes).orEmpty(),
            timestamp = modifiedAt ?: 0L,
            signature = pickcode,
            provider = SubtitleSearchProvider.Cloud115
        )
    }

    private fun String.matchesSubtitleNumber(number: String): Boolean {
        val expected = number.uppercase(Locale.ROOT)
        val upperName = uppercase(Locale.ROOT)
        if (upperName.contains(expected)) return true
        return normalizeMovieNumber(this)?.uppercase(Locale.ROOT) == expected
    }

    companion object {
        private const val SEARCH_LIMIT = 64
        private const val SEARCH_TYPE_ALL = 99
        private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    }
}

fun normalizeCloud115SubtitleNumber(number: String): String =
    number.trim().uppercase(Locale.ROOT).replace("_", "-")

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.ROOT, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
