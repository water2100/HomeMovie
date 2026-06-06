package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.playbackSourceSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Cloud115StrmRepository(
    private val context: Context,
    private val cloud115Client: Cloud115Client,
    private val settingsRepository: AppSettingsRepository,
    private val recordRepository: CloudStrmRecordRepository
) {
    suspend fun listFiles(cid: Long): List<Cloud115FileItem> = cloud115Client.listFiles(cid)

    suspend fun existingPickcodesForVisibleItems(items: List<Cloud115FileItem>): Set<String> = withContext(Dispatchers.IO) {
        val videoItemsByPickcode = items
            .filter { !it.isDirectory && it.isVideoFile() }
            .mapNotNull { item ->
                item.pickcode
                    ?.takeIf { pickcode -> pickcode.isNotBlank() }
                    ?.let { pickcode -> pickcode to item }
            }
            .toMap()
        val records = recordRepository.existingRecordsForVisibleItems(videoItemsByPickcode.keys)
        records
            .filter { record ->
                val itemNumber = videoItemsByPickcode[record.pickcode]
                    ?.name
                    ?.let { extractMovieNumberInfo(it)?.number }
                itemNumber == null || record.movieNumber == itemNumber
            }
            .map { it.pickcode }
            .toSet()
    }

    suspend fun generateStrmForVideo(item: Cloud115FileItem, forceDistinct: Boolean = false): GeneratedStrmFile = withContext(Dispatchers.IO) {
        if (item.isDirectory) error("请选择一个视频文件")
        if (!item.isVideoFile()) error("当前文件不是支持的视频格式")
        val pickcode = item.pickcode?.takeIf { it.isNotBlank() } ?: error("这个文件没有 pickcode，无法生成 STRM")
        val targetRoot = requireWritableStrmRoot()

        val segmentInfo = extractMovieNumberInfo(item.name)
        val variant = detectMovieVariant(item.name)

        if (!forceDistinct) {
            recordRepository.getCached(pickcode)
                ?.takeIf { existing ->
                    segmentInfo == null || existing.movieNumber == segmentInfo.number
                }
                ?.let { existing ->
                return@withContext GeneratedStrmFile(
                    fileName = existing.fileName,
                    pickcode = pickcode,
                    strmUri = existing.strmUri,
                    created = false,
                    movieNumberHint = existing.movieNumber,
                    partLabel = existing.partLabel
                )
            }
        }

        if (
            segmentInfo != null &&
            !forceDistinct &&
            (segmentInfo.partLabel != null || variant != MovieVariant.Standard)
        ) {
            findExistingMovieDirectoryFast(targetRoot, segmentInfo.number)?.let { movieDirectory ->
                val sourceSuffix = playbackSourceSuffix(segmentInfo.partLabel, variant)
                val variantFile = writeStrmFile(
                    root = movieDirectory,
                    videoName = item.name,
                    pickcode = pickcode,
                    forcedBaseName = "${movieDirectory.name}$sourceSuffix"
                )
                recordRepository.upsertGenerated(
                    pickcode = pickcode,
                    fileName = variantFile.name,
                    strmUri = variantFile.uri,
                    libraryRootUri = settingsRepository.getLibraryRootUri()
                )
                return@withContext GeneratedStrmFile(
                    fileName = variantFile.name,
                    pickcode = pickcode,
                    strmUri = variantFile.uri,
                    created = true,
                    shouldScrape = false,
                    movieNumberHint = segmentInfo.number,
                    partLabel = segmentInfo.partLabel
                )
            }
        }
        if (
            segmentInfo != null &&
            !forceDistinct &&
            segmentInfo.partLabel == null &&
            variant == MovieVariant.Standard
        ) {
            findExistingMovieDirectoryFast(targetRoot, segmentInfo.number)?.let { movieDirectory ->
                val standardFile = writeStrmFile(
                    root = movieDirectory,
                    videoName = item.name,
                    pickcode = pickcode,
                    forcedBaseName = movieDirectory.name
                )
                recordRepository.upsertGenerated(
                    pickcode = pickcode,
                    fileName = standardFile.name,
                    strmUri = standardFile.uri,
                    libraryRootUri = settingsRepository.getLibraryRootUri()
                )
                return@withContext GeneratedStrmFile(
                    fileName = standardFile.name,
                    pickcode = pickcode,
                    strmUri = standardFile.uri,
                    created = true,
                    shouldScrape = false,
                    movieNumberHint = segmentInfo.number
                )
            }
        }

        val forcedBaseName = if (forceDistinct) {
            item.name.substringBeforeLast('.', item.name) + "_$pickcode"
        } else {
            null
        }
        val file = writeStrmFile(targetRoot, item.name, pickcode, forcedBaseName = forcedBaseName)
        recordRepository.upsertGenerated(
            pickcode = pickcode,
            fileName = file.name,
            strmUri = file.uri,
            libraryRootUri = settingsRepository.getLibraryRootUri()
        )
        GeneratedStrmFile(
            fileName = file.name,
            pickcode = pickcode,
            strmUri = file.uri,
            created = true,
            forceDistinct = forceDistinct,
            movieNumberHint = extractMovieNumberInfo(file.name)?.number,
            partLabel = extractMovieNumberInfo(file.name)?.partLabel
        )
    }

    private fun requireWritableStrmRoot(): DocumentFile {
        val treeUri = settingsRepository.getStrmTreeUri()
            ?: error("请先到设置页选择 STRM 文件保存位置")
        val targetRoot = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("STRM 保存目录不可用")
        if (!targetRoot.canWrite()) error("STRM 保存目录没有写入权限")
        return targetRoot
    }

    private fun writeStrmFile(root: DocumentFile, videoName: String, pickcode: String, forcedBaseName: String? = null): WrittenStrmFile {
        val baseName = (forcedBaseName ?: videoName.substringBeforeLast('.', videoName)).sanitizeFileName()
        val fileName = uniqueStrmName(root, baseName, pickcode)
        val file = root.createFile("application/octet-stream", fileName)
            ?: error("无法创建 STRM 文件：$fileName")
        val encodedName = Uri.encode(videoName)
        val content = "${settingsRepository.getStrmBaseUrl()}/download_m3u/$pickcode/$encodedName"
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入 STRM 文件：$fileName")
        return WrittenStrmFile(name = fileName, uri = file.uri.toString())
    }

    private fun uniqueStrmName(root: DocumentFile, baseName: String, pickcode: String): String {
        val first = "$baseName.strm"
        if (root.findFile(first) == null) return first
        return "${baseName}_$pickcode.strm"
    }

    private suspend fun findExistingMovieDirectoryFast(root: DocumentFile, number: String): DocumentFile? {
        recordRepository.getByMovieNumber(number)
            .asSequence()
            .mapNotNull { record -> findParentDirectory(root, record.strmUri) }
            .firstOrNull()
            ?.let { return it }
        return null
    }

    private fun findParentDirectory(root: DocumentFile, fileUriString: String): DocumentFile? {
        val treeUri = settingsRepository.getStrmTreeUri() ?: return null
        val rootDocId = Uri.parse(treeUri).treeDocumentId() ?: return null
        val fileDocId = Uri.parse(fileUriString).documentId() ?: return null
        val parentDocId = fileDocId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocId.isBlank() || parentDocId == rootDocId) return null
        if (!parentDocId.startsWith("$rootDocId/")) return null
        val relativePath = parentDocId.removePrefix("$rootDocId/")
        return relativePath.split('/')
            .filter { it.isNotBlank() }
            .fold(root as DocumentFile?) { current, segment ->
                current?.findFile(segment)?.takeIf { it.isDirectory }
            }
            ?.takeIf { it.findFileByUri(fileUriString) != null }
    }

    private fun DocumentFile.findFileByUri(uriString: String): DocumentFile? {
        return listFiles().firstOrNull { it.isFile && it.uri.toString() == uriString }
    }

    private fun Uri.treeDocumentId(): String? {
        val index = pathSegments.indexOf("tree")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun Uri.documentId(): String? {
        val index = pathSegments.indexOf("document")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun Cloud115FileItem.isVideoFile(): Boolean =
        VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "video" }

    companion object {
        val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".ts", ".iso", ".flv", ".webm")
    }
}

data class GeneratedStrmFile(
    val fileName: String,
    val pickcode: String,
    val strmUri: String,
    val created: Boolean,
    val shouldScrape: Boolean = true,
    val forceDistinct: Boolean = false,
    val movieNumberHint: String? = null,
    val partLabel: String? = null
)

private data class WrittenStrmFile(
    val name: String,
    val uri: String
)
