package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class SavedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String
)

class LiveSubtitleStore(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository
) {
    private val appContext = context.applicationContext

    suspend fun load(videoUri: Uri, fileName: String): List<SavedSubtitleCue> = withContext(Dispatchers.IO) {
        val text = findSubtitleFile(videoUri, fileName)?.readText().orEmpty()
        if (text.isBlank()) emptyList() else parseSrt(text)
    }

    suspend fun append(
        videoUri: Uri,
        fileName: String,
        sourceText: String,
        translatedText: String,
        positionMs: Long
    ) = withContext(Dispatchers.IO) {
        if (sourceText.isBlank() && translatedText.isBlank()) return@withContext
        val subtitleFile = findOrCreateSubtitleFile(videoUri, fileName) ?: return@withContext
        val existing = subtitleFile.readText()
        val index = nextCueIndex(existing)
        val endMs = positionMs.coerceAtLeast(0L)
        val startMs = (endMs - DEFAULT_CUE_DURATION_MS).coerceAtLeast(0L)
        val block = buildString {
            if (existing.isNotBlank() && !existing.endsWith("\n")) append('\n')
            append(index).append('\n')
            append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs + 1_200L)).append('\n')
            if (sourceText.isNotBlank()) append(sourceText.trim()).append('\n')
            if (translatedText.isNotBlank()) append(translatedText.trim()).append('\n')
            append('\n')
        }
        subtitleFile.appendText(block)
    }

    private fun findSubtitleFile(videoUri: Uri, fileName: String): SubtitleTarget? {
        val subtitleName = subtitleFileName(videoUri, fileName)
        return resolveParent(videoUri)?.findFile(subtitleName)?.let { SubtitleTarget.Document(it, appContext) }
            ?: resolveLocalParent(videoUri)?.resolve(subtitleName)?.takeIf { it.exists() }?.let { SubtitleTarget.Local(it) }
    }

    private fun findOrCreateSubtitleFile(videoUri: Uri, fileName: String): SubtitleTarget? {
        val subtitleName = subtitleFileName(videoUri, fileName)
        val documentParent = resolveParent(videoUri)
        if (documentParent != null) {
            val existing = documentParent.findFile(subtitleName)
            val file = existing ?: documentParent.createFile("application/x-subrip", subtitleName)
            if (file != null) return SubtitleTarget.Document(file, appContext)
        }
        val localParent = resolveLocalParent(videoUri) ?: return null
        if (!localParent.exists()) localParent.mkdirs()
        return SubtitleTarget.Local(localParent.resolve(subtitleName))
    }

    private fun resolveParent(videoUri: Uri): DocumentFile? {
        if (videoUri.scheme != "content") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return null
        val roots = settingsRepository.getKnownLibraryRootUris() + settingsRepository.getKnownStrmTreeUris()
        for (root in roots) {
            val rootUri = runCatching { Uri.parse(root) }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull() ?: continue
            if (documentId != rootId && !documentId.startsWith("$rootId/")) continue
            var current = DocumentFile.fromTreeUri(appContext, rootUri) ?: continue
            val relative = documentId.removePrefix(rootId).trimStart('/')
            val parentSegments = relative.split('/').filter { it.isNotBlank() }.dropLast(1)
            var resolved = true
            for (segment in parentSegments) {
                val next = current.findFile(segment)
                if (next == null) {
                    resolved = false
                    break
                } else {
                    current = next
                }
            }
            if (resolved) return current
        }
        return null
    }

    private fun resolveLocalParent(videoUri: Uri): File? {
        val path = when (videoUri.scheme) {
            "file" -> videoUri.path
            null -> videoUri.path
            else -> null
        } ?: return null
        return File(path).parentFile
    }

    private fun subtitleFileName(videoUri: Uri, fileName: String): String {
        val rawName = fileName.ifBlank {
            DocumentFile.fromSingleUri(appContext, videoUri)?.name.orEmpty()
        }
        val base = rawName.substringBeforeLast('.', rawName).ifBlank { "live-subtitle" }
        return "$base.ai.zh.srt"
    }

    private fun parseSrt(text: String): List<SavedSubtitleCue> {
        return text.replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .mapNotNull { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
                val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
                if (timeLineIndex < 0) return@mapNotNull null
                val timeParts = lines[timeLineIndex].split("-->").map { it.trim() }
                val start = parseSrtTime(timeParts.getOrNull(0).orEmpty()) ?: return@mapNotNull null
                val end = parseSrtTime(timeParts.getOrNull(1).orEmpty()) ?: start + DEFAULT_CUE_DURATION_MS
                val cueLines = lines.drop(timeLineIndex + 1)
                SavedSubtitleCue(
                    startMs = start,
                    endMs = end,
                    sourceText = cueLines.firstOrNull().orEmpty(),
                    translatedText = cueLines.drop(1).joinToString("\n")
                )
            }
    }

    private fun nextCueIndex(existing: String): Int {
        if (existing.isBlank()) return 1
        return parseSrt(existing).size + 1
    }

    private fun formatSrtTime(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        val millis = safeMs % 1_000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun parseSrtTime(value: String): Long? {
        val match = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})""").find(value) ?: return null
        val (hours, minutes, seconds, millis) = match.destructured
        return hours.toLong() * 3_600_000L +
            minutes.toLong() * 60_000L +
            seconds.toLong() * 1_000L +
            millis.toLong()
    }

    private sealed interface SubtitleTarget {
        fun readText(): String
        fun appendText(text: String)

        data class Document(
            val file: DocumentFile,
            val context: Context
        ) : SubtitleTarget {
            override fun readText(): String =
                runCatching {
                    context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()

            override fun appendText(text: String) {
                context.contentResolver.openOutputStream(file.uri, "wa")?.bufferedWriter()?.use { it.write(text) }
            }
        }

        data class Local(val file: File) : SubtitleTarget {
            override fun readText(): String = runCatching { file.readText() }.getOrNull().orEmpty()

            override fun appendText(text: String) {
                file.appendText(text)
            }
        }
    }

    companion object {
        private const val DEFAULT_CUE_DURATION_MS = 4_000L
    }
}
