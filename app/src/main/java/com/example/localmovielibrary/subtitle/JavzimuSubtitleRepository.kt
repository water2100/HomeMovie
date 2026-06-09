package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.diagnostics.RuntimeErrorLog
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import com.example.localmovielibrary.util.normalizeMovieNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class JavzimuCloudflareException(message: String) : IllegalStateException(message)

data class LocalSubtitleFile(
    val name: String,
    val uri: Uri
)

data class JavzimuSubtitleResult(
    val cid: String,
    val ext: String,
    val name: String,
    val durationMs: Long?,
    val language: String,
    val extraName: String,
    val timestamp: Long,
    val signature: String,
    val provider: SubtitleSearchProvider = SubtitleSearchProvider.Javzimu
) {
    val displayName: String = buildString {
        append(provider.label)
        if (language.isNotBlank()) append(" 路 ").append(language)
        if (extraName.isNotBlank()) append(" 路 ").append(extraName)
        durationMs?.takeIf { it > 0L }?.let { append(" 路 ").append(formatDuration(it)) }
    }
}

class JavzimuSubtitleRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val errorLog = RuntimeErrorLog(appContext)

    suspend fun listLocalSubtitles(
        videoUri: Uri,
        fileName: String,
        storageSourceUri: Uri? = null
    ): List<LocalSubtitleFile> =
        withContext(Dispatchers.IO) {
            val baseNames = subtitleBaseNames(videoUri, fileName)
            val candidates = storageCandidates(videoUri, storageSourceUri)
            val documentParents = candidates
                .mapNotNull { resolveDocumentParent(it) }
                .distinctBy { it.uri.toString() }
                .ifEmpty { fallbackDocumentParents(videoUri, fileName) }
            val documentFiles = documentParents
                .flatMap { parent ->
                    parent.listFiles()
                        .filter { it.isFile && it.name.orEmpty().isSubtitleNameFor(baseNames) }
                        .map { LocalSubtitleFile(it.name.orEmpty(), it.uri) }
                }
            val localFiles = candidates
                .mapNotNull { resolveLocalParent(it) }
                .distinctBy { it.absolutePath }
                .flatMap { parent ->
                    parent.listFiles()
                        ?.filter { it.isFile && it.name.isSubtitleNameFor(baseNames) }
                        ?.map { LocalSubtitleFile(it.name, Uri.fromFile(it)) }
                        .orEmpty()
                }
            (documentFiles + localFiles).distinctBy { it.uri.toString() }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }

    suspend fun deleteLocalSubtitle(subtitle: LocalSubtitleFile): Boolean = withContext(Dispatchers.IO) {
        when (subtitle.uri.scheme) {
            "file", null -> {
                val path = subtitle.uri.path ?: return@withContext false
                File(path).takeIf { it.isFile }?.delete() == true
            }
            "content" -> {
                DocumentFile.fromSingleUri(appContext, subtitle.uri)?.delete() == true ||
                    runCatching {
                        appContext.contentResolver.delete(subtitle.uri, null, null) > 0
                    }.getOrDefault(false)
            }
            else -> false
        }
    }

    suspend fun search(number: String, videoDurationMs: Long): List<JavzimuSubtitleResult> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeJavzimuSubtitleNumber(number)
            if (normalized.isBlank()) return@withContext emptyList()
            val url = "$BASE_URL/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", normalized)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .applyJavzimuCookie()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 403 || body.isJavzimuCloudflareChallengeHtml()) {
                    throw JavzimuCloudflareException("Javzimu 需要通过 WebView 获取 Cookie")
                }
                if (!response.isSuccessful) error("\u5B57\u5E55\u641C\u7D22\u5931\u8D25\uFF1AHTTP ${response.code}")
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 0) error("\u5B57\u5E55\u641C\u7D22\u5931\u8D25\uFF1A${json.optString("result", "\u672A\u77E5\u9519\u8BEF")}")
                val data = json.optJSONArray("data") ?: return@withContext emptyList()
                buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val result = item.toSubtitleResult() ?: continue
                        if (result.isCloseTo(videoDurationMs)) add(result)
                    }
                }
            }
        }

    suspend fun download(
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = saveSubtitleBytes(
        videoUri = videoUri,
        fileName = fileName,
        result = result,
        bytes = withContext(Dispatchers.IO) { downloadBytes(result) },
        storageSourceUri = storageSourceUri
    )

    suspend fun saveSubtitleBytes(
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        bytes: ByteArray,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = withContext(Dispatchers.IO) {
        val candidates = storageCandidates(videoUri, storageSourceUri)
        val directDocumentParents = candidates
            .mapNotNull { resolveDocumentParent(it) }
            .distinctBy { it.uri.toString() }
        val fallbackParents = if (directDocumentParents.any { isWritableDocumentParent(it) }) {
            emptyList()
        } else {
            fallbackDocumentParents(videoUri, fileName)
        }
        val documentParents = (directDocumentParents + fallbackParents)
            .distinctBy { it.uri.toString() }
        val writableDocumentParents = documentParents.filter { isWritableDocumentParent(it) }
        val localParent = candidates.firstNotNullOfOrNull { resolveLocalParent(it) }
        errorLog.append(
            event = "javzimu.subtitle.download.begin",
            details = mapOf(
                "videoUri" to videoUri.toString(),
                "fileName" to fileName,
                "storageSourceUri" to storageSourceUri?.toString(),
                "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" },
                "writableDocumentParents" to writableDocumentParents.joinToString(" | ") { "${it.name}:${it.uri}" },
                "hasLocalParent" to (localParent != null).toString(),
                "resultName" to result.name,
                "resultExt" to result.ext
            )
        )
        if (writableDocumentParents.isEmpty() && localParent == null) {
            val message = if (documentParents.isNotEmpty()) {
                "\u65E0\u6CD5\u5728\u89C6\u9891\u76EE\u5F55\u521B\u5EFA\u5B57\u5E55\u6587\u4EF6\uFF0C\u8BF7\u5728\u76EE\u5F55\u8BBE\u7F6E\u4E2D\u91CD\u65B0\u9009\u62E9\u5F71\u7247\u5E93\u76EE\u5F55\u5E76\u6388\u4E88\u5199\u5165\u6743\u9650"
            } else {
                "\u65E0\u6CD5\u5B9A\u4F4D\u89C6\u9891\u6240\u5728\u76EE\u5F55"
            }
            errorLog.append(
                event = if (documentParents.isNotEmpty()) {
                    "javzimu.subtitle.download.noWritableParentBeforeRequest"
                } else {
                    "javzimu.subtitle.download.noParentBeforeRequest"
                },
                details = mapOf(
                    "videoUri" to videoUri.toString(),
                    "fileName" to fileName,
                    "storageSourceUri" to storageSourceUri?.toString(),
                    "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                    "documentParentCount" to documentParents.size.toString(),
                    "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" }
                )
            )
            error(message)
        }
        for (documentParent in writableDocumentParents) {
            writeSubtitleToDocumentParent(documentParent, videoUri, fileName, result, bytes)
                ?.let { return@withContext it }
        }
        val targetName = uniqueSubtitleName(videoUri, fileName, result, storageSourceUri)
        val targetLocalParent = localParent
            ?: run {
                val message = if (documentParents.isNotEmpty()) {
                    "\u65E0\u6CD5\u5728\u89C6\u9891\u76EE\u5F55\u521B\u5EFA\u5B57\u5E55\u6587\u4EF6\uFF0C\u8BF7\u5728\u76EE\u5F55\u8BBE\u7F6E\u4E2D\u91CD\u65B0\u9009\u62E9\u5F71\u7247\u5E93\u76EE\u5F55\u5E76\u6388\u4E88\u5199\u5165\u6743\u9650"
                } else {
                    "\u65E0\u6CD5\u5B9A\u4F4D\u89C6\u9891\u6240\u5728\u76EE\u5F55"
                }
                errorLog.append(
                    event = if (documentParents.isNotEmpty()) {
                        "javzimu.subtitle.download.noWritableParent"
                    } else {
                        "javzimu.subtitle.download.noLocalParent"
                    },
                    details = mapOf(
                        "videoUri" to videoUri.toString(),
                        "fileName" to fileName,
                        "storageSourceUri" to storageSourceUri?.toString(),
                        "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                        "documentParentCount" to documentParents.size.toString(),
                        "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" }
                    )
                )
                error(message)
            }
        if (!targetLocalParent.exists()) targetLocalParent.mkdirs()
        val file = targetLocalParent.resolve(targetName)
        runCatching { file.writeBytes(bytes) }.getOrElse { error ->
            errorLog.append(
                event = "javzimu.subtitle.download.localWriteFailed",
                details = mapOf(
                    "targetFile" to file.absolutePath,
                    "targetName" to targetName
                ),
                error = error
            )
            throw error
        }
        LocalSubtitleFile(file.name, Uri.fromFile(file))
    }

    private fun writeSubtitleToDocumentParent(
        documentParent: DocumentFile,
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        bytes: ByteArray
    ): LocalSubtitleFile? {
        val preferredName = uniqueSubtitleName(videoUri, fileName, result, documentParent)
        val fallbackBase = normalizeMovieNumber(fileName)
            ?: normalizeMovieNumber(videoUri.toString())
            ?: result.name.substringBeforeLast('.', result.name).sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val nameCandidates = listOf(
            preferredName,
            "${fallbackBase.sanitizeFileName()}.$ext",
            "${fallbackBase.sanitizeFileName()}.javzimu.$ext",
            "subtitle.$ext"
        ).distinct()
        for (targetName in nameCandidates) {
            val file = createSubtitleDocument(documentParent, ext, targetName) ?: continue
            val wrote = runCatching {
                appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } != null
            }.onFailure { error ->
                errorLog.append(
                    event = "javzimu.subtitle.download.documentWriteFailed",
                    details = mapOf(
                        "parentName" to documentParent.name,
                        "parentUri" to documentParent.uri.toString(),
                        "targetName" to targetName,
                        "createdName" to file.name,
                        "createdUri" to file.uri.toString()
                    ),
                    error = error
                )
            }.getOrDefault(false)
            if (wrote) {
                errorLog.append(
                    event = "javzimu.subtitle.download.documentWriteSuccess",
                    details = mapOf(
                        "parentName" to documentParent.name,
                        "parentUri" to documentParent.uri.toString(),
                        "targetName" to targetName,
                        "createdName" to file.name,
                        "createdUri" to file.uri.toString()
                    )
                )
                return LocalSubtitleFile(file.name.orEmpty().ifBlank { targetName }, file.uri)
            }
            runCatching { file.delete() }
        }
        errorLog.append(
            event = "javzimu.subtitle.download.documentCreateFailed",
            details = mapOf(
                "parentName" to documentParent.name,
                "parentUri" to documentParent.uri.toString(),
                "fileName" to fileName,
                "nameCandidates" to nameCandidates.joinToString(" | "),
                "ext" to ext
            )
        )
        return null
    }

    private fun downloadBytes(result: JavzimuSubtitleResult): ByteArray {
        val cookie = settingsRepository.getJavzimuCookies()
        val url = "$BASE_URL/api/download".toHttpUrl().newBuilder()
            .addQueryParameter("cid", result.cid)
            .addQueryParameter("ext", result.ext)
            .addQueryParameter("name", result.name)
            .addQueryParameter("_ts", result.timestamp.toString())
            .addQueryParameter("_sig", result.signature)
            .build()
        errorLog.append(
            event = "javzimu.subtitle.download.request",
            details = mapOf(
                "url" to url.toString(),
                "resultName" to result.name,
                "resultExt" to result.ext,
                "hasJavzimuCookie" to cookie.isNotBlank().toString(),
                "javzimuCookieLength" to cookie.length.toString()
            )
        )
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .applyJavzimuCookie(cookie)
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.code == 403 || bytes.decodeToString().isJavzimuCloudflareChallengeHtml()) {
                throw JavzimuCloudflareException("Javzimu 需要通过 WebView 获取 Cookie")
            }
            if (!response.isSuccessful) error("\u5B57\u5E55\u4E0B\u8F7D\u5931\u8D25\uFF1AHTTP ${response.code}")
            if (bytes.isEmpty()) error("\u5B57\u5E55\u4E0B\u8F7D\u7ED3\u679C\u4E3A\u7A7A")
            return bytes
        }
    }

    private fun Request.Builder.applyJavzimuCookie(): Request.Builder {
        return applyJavzimuCookie(settingsRepository.getJavzimuCookies())
    }

    private fun Request.Builder.applyJavzimuCookie(cookie: String): Request.Builder {
        if (cookie.isNotBlank()) header("Cookie", cookie)
        return this
    }

    private fun JSONObject.toSubtitleResult(): JavzimuSubtitleResult? {
        val cid = optString("cid").ifBlank { optString("gcid") }
        val ext = optString("ext").ifBlank { "srt" }.lowercase(Locale.ROOT)
        val name = optString("name").ifBlank { "$cid.$ext" }
        val timestamp = optLong("_ts", 0L)
        val signature = optString("_sig")
        if (cid.isBlank() || signature.isBlank() || timestamp <= 0L) return null
        val rawDuration = optLong("duration", 0L)
        val durationMs = when {
            rawDuration <= 0L -> null
            rawDuration < 10_000L -> rawDuration * 1_000L
            rawDuration < 24L * 60L * 60L * 1_000L -> rawDuration
            else -> null
        }
        val languages = optJSONArray("languages")
        return JavzimuSubtitleResult(
            cid = cid,
            ext = ext,
            name = name,
            durationMs = durationMs,
            language = languages?.optString(0).orEmpty(),
            extraName = optString("extra_name"),
            timestamp = timestamp,
            signature = signature
        )
    }

    private fun JavzimuSubtitleResult.isCloseTo(videoDurationMs: Long): Boolean {
        val subtitleDuration = durationMs ?: return true
        if (videoDurationMs <= 0L) return true
        return abs(subtitleDuration - videoDurationMs) <= MAX_DURATION_DIFF_MS
    }

    private fun uniqueSubtitleName(
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        storageSourceUri: Uri?
    ): String {
        val base = subtitleBaseNames(videoUri, fileName).firstOrNull().orEmpty().ifBlank {
            result.name.substringBeforeLast('.', result.name)
        }
        val safeBase = base.sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val preferred = "$safeBase.${result.provider.fileSuffix}.$ext"
        val used = listLocalSubtitleNames(videoUri, storageSourceUri).map { it.lowercase(Locale.ROOT) }.toSet()
        if (preferred.lowercase(Locale.ROOT) !in used) return preferred
        var index = 2
        while (true) {
            val name = "$safeBase.${result.provider.fileSuffix}-$index.$ext"
            if (name.lowercase(Locale.ROOT) !in used) return name
            index++
        }
    }

    private fun uniqueSubtitleName(
        videoUri: Uri,
        fileName: String,
        result: JavzimuSubtitleResult,
        documentParent: DocumentFile
    ): String {
        val base = subtitleBaseNames(videoUri, fileName).firstOrNull().orEmpty().ifBlank {
            result.name.substringBeforeLast('.', result.name)
        }
        val safeBase = base.sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val used = documentParent.listFiles()
            .mapNotNull { it.name }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val preferred = "$safeBase.${result.provider.fileSuffix}.$ext"
        if (preferred.lowercase(Locale.ROOT) !in used) return preferred
        var index = 2
        while (true) {
            val name = "$safeBase.${result.provider.fileSuffix}-$index.$ext"
            if (name.lowercase(Locale.ROOT) !in used) return name
            index++
        }
    }

    private fun createSubtitleDocument(parent: DocumentFile, ext: String, targetName: String): DocumentFile? {
        val mime = mimeTypeFor(ext)
        val nameWithoutExt = targetName.substringBeforeLast('.', targetName)
        return runCreateSubtitleDocument(parent, mime, targetName)
            ?: runCreateSubtitleDocument(parent, "text/plain", targetName)
            ?: runCreateSubtitleDocument(parent, "application/octet-stream", targetName)
            ?: runCreateSubtitleDocument(parent, mime, nameWithoutExt)
            ?: runCreateSubtitleDocument(parent, "text/plain", nameWithoutExt)
            ?: createSubtitleDocumentDirect(parent, mime, targetName)
            ?: createSubtitleDocumentDirect(parent, "text/plain", targetName)
            ?: createSubtitleDocumentDirect(parent, "application/octet-stream", targetName)
    }

    private fun runCreateSubtitleDocument(parent: DocumentFile, mimeType: String, targetName: String): DocumentFile? =
        runCatching {
            parent.createFile(mimeType, targetName)
        }.onFailure { error ->
            errorLog.append(
                event = "javzimu.subtitle.download.documentFileCreateException",
                details = mapOf(
                    "parentName" to parent.name,
                    "parentUri" to parent.uri.toString(),
                    "mimeType" to mimeType,
                    "targetName" to targetName
                ),
                error = error
            )
        }.getOrNull()

    private fun createSubtitleDocumentDirect(parent: DocumentFile, mimeType: String, targetName: String): DocumentFile? {
        val uri = runCatching {
            DocumentsContract.createDocument(appContext.contentResolver, parent.uri, mimeType, targetName)
        }.onFailure { error ->
            errorLog.append(
                event = "javzimu.subtitle.download.documentsContractCreateException",
                details = mapOf(
                    "parentName" to parent.name,
                    "parentUri" to parent.uri.toString(),
                    "mimeType" to mimeType,
                    "targetName" to targetName
                ),
                error = error
            )
        }.getOrNull() ?: return null
        return DocumentFile.fromSingleUri(appContext, uri)
    }

    private fun listLocalSubtitleNames(videoUri: Uri, storageSourceUri: Uri?): List<String> {
        val candidates = storageCandidates(videoUri, storageSourceUri)
        val documentNames = candidates
            .mapNotNull { resolveDocumentParent(it) }
            .distinctBy { it.uri.toString() }
            .flatMap { it.listFiles().mapNotNull { file -> file.name } }
        val localNames = candidates
            .mapNotNull { resolveLocalParent(it) }
            .distinctBy { it.absolutePath }
            .flatMap { it.listFiles()?.map { file -> file.name }.orEmpty() }
        return documentNames + localNames
    }

    private fun storageCandidates(videoUri: Uri, storageSourceUri: Uri?): List<Uri> =
        listOfNotNull(storageSourceUri, videoUri).distinctBy { it.toString() }

    private fun subtitleBaseNames(videoUri: Uri, fileName: String): List<String> {
        val rawName = fileName.ifBlank {
            DocumentFile.fromSingleUri(appContext, videoUri)?.name.orEmpty()
        }.substringBeforeLast('.', fileName)
        return listOf(rawName, rawName.substringBeforeLast('.', rawName))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveDocumentParent(videoUri: Uri): DocumentFile? {
        if (videoUri.scheme != "content") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return null
        val roots = listOf(videoUri.toString()) + settingsRepository.getKnownLibraryRootUris() + settingsRepository.getKnownStrmTreeUris()
        for (root in roots) {
            val rootUri = runCatching { Uri.parse(root) }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull() ?: continue
            if (documentId != rootId && !documentId.startsWith("$rootId/")) continue
            val treeUri = DocumentsContract.buildTreeDocumentUri(rootUri.authority, rootId)
            var current = DocumentFile.fromTreeUri(appContext, treeUri) ?: continue
            val relative = documentId.removePrefix(rootId).trimStart('/')
            val parentSegments = relative.split('/').filter { it.isNotBlank() }.dropLast(1)
            var resolved = true
            for (segment in parentSegments) {
                val next = current.findFile(segment)
                if (next == null) {
                    resolved = false
                    break
                }
                current = next
            }
            if (resolved) return current
        }
        return null
    }

    private fun isWritableDocumentParent(parent: DocumentFile): Boolean {
        if (!parent.canWrite()) return false
        return hasPersistedWritePermission(parent.uri)
    }

    private fun hasPersistedWritePermission(uri: Uri): Boolean {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val permissions = appContext.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == uri.authority }
        if (permissions.isEmpty()) return false
        return permissions.any { permission ->
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
            when {
                rootId != null && documentId != null -> documentId == rootId || documentId.startsWith("$rootId/")
                else -> permission.uri == uri
            }
        }
    }

    private fun fallbackDocumentParents(videoUri: Uri, fileName: String): List<DocumentFile> {
        val numbers = subtitleNumberCandidates(videoUri, fileName)
        if (numbers.isEmpty()) return emptyList()
        val roots = (settingsRepository.getKnownLibraryRootUris() + settingsRepository.getKnownStrmTreeUris())
            .filter { it.isNotBlank() }
            .distinct()
        return roots.mapNotNull { root ->
            val rootUri = runCatching { Uri.parse(root) }.getOrNull() ?: return@mapNotNull null
            val rootFile = DocumentFile.fromTreeUri(appContext, rootUri) ?: return@mapNotNull null
            findDirectoryContainingMovieNumber(rootFile, numbers)
        }.distinctBy { it.uri.toString() }
    }

    private fun subtitleNumberCandidates(videoUri: Uri, fileName: String): Set<String> =
        buildSet {
            normalizeMovieNumber(fileName)?.let { add(it) }
            normalizeMovieNumber(videoUri.toString())?.let { add(it) }
            DocumentFile.fromSingleUri(appContext, videoUri)?.name
                ?.let { normalizeMovieNumber(it) }
                ?.let { add(it) }
        }

    private fun findDirectoryContainingMovieNumber(directory: DocumentFile, numbers: Set<String>): DocumentFile? {
        directory.listFiles().forEach { child ->
            if (child.isDirectory) {
                findDirectoryContainingMovieNumber(child, numbers)?.let { return it }
            } else if (child.isFile && child.name.orEmpty().isMovieSourceFor(numbers)) {
                return directory
            }
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

    private fun String.isSubtitleNameFor(baseNames: List<String>): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (substringAfterLast('.', "").lowercase(Locale.ROOT) !in SUBTITLE_EXTENSIONS) return false
        val nameBase = substringBeforeLast('.', this).lowercase(Locale.ROOT)
        return baseNames.any { base ->
            val normalized = base.lowercase(Locale.ROOT)
            nameBase == normalized || nameBase.startsWith("$normalized.") || nameBase.startsWith("$normalized-")
        }
    }

    private fun String.isMovieSourceFor(numbers: Set<String>): Boolean {
        val ext = substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in VIDEO_OR_STRM_EXTENSIONS) return false
        val number = normalizeMovieNumber(this) ?: return false
        return number in numbers
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "subtitle" }

    private fun mimeTypeFor(ext: String): String =
        when (ext.lowercase(Locale.ROOT)) {
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "application/x-subrip"
        }

    companion object {
        private const val BASE_URL = "https://javzimu.com"
        private const val MAX_DURATION_DIFF_MS = 10 * 60 * 1_000L
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
        private val VIDEO_OR_STRM_EXTENSIONS = setOf("strm", "mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg")
    }
}

fun normalizeJavzimuSubtitleNumber(number: String): String {
    val value = number.trim().uppercase(Locale.ROOT)
    val match = Regex("""^([A-Z]+)-0*(\d+)$""").matchEntire(value) ?: return value
    val prefix = match.groupValues[1]
    val number = match.groupValues[2].toIntOrNull()?.toString() ?: match.groupValues[2]
    return "$prefix-$number"
}

private fun String.isJavzimuCloudflareChallengeHtml(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

