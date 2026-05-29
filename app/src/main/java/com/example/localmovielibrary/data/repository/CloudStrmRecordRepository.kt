package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.CloudStrmRecordDao
import com.example.localmovielibrary.data.local.CloudStrmRecordEntity
import com.example.localmovielibrary.data.local.MovieDao
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.playbackSourceSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudStrmRecordRepository(
    private val context: Context,
    private val dao: CloudStrmRecordDao,
    private val movieDao: MovieDao,
    private val settingsRepository: AppSettingsRepository
) {
    @Volatile
    private var hasIndexedKnownMovies = false

    suspend fun get(pickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        dao.get(pickcode) ?: run {
            ensureKnownMoviesIndexed()
            dao.get(pickcode)
        }
    }

    suspend fun getCached(pickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        dao.get(pickcode)
    }

    suspend fun existingPickcodes(): Set<String> = withContext(Dispatchers.IO) {
        dao.getAllPickcodes().toSet()
    }

    suspend fun existingPickcodesForVisibleItems(pickcodes: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        if (pickcodes.isEmpty()) return@withContext emptySet()
        dao.getByPickcodes(pickcodes.toList()).map { it.pickcode }.toSet()
    }

    suspend fun indexKnownMoviesIfNeeded(): Set<String> = withContext(Dispatchers.IO) {
        ensureKnownMoviesIndexed()
        dao.getAllPickcodes().toSet()
    }

    suspend fun upsertGenerated(
        pickcode: String,
        fileName: String,
        strmUri: String,
        libraryRootUri: String?,
        movieId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = dao.get(pickcode)
        val info = extractMovieNumberInfo(fileName)
        dao.upsert(
            CloudStrmRecordEntity(
                pickcode = pickcode,
                fileName = fileName,
                movieNumber = info?.number,
                variant = detectMovieVariant(fileName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = strmUri,
                libraryRootUri = libraryRootUri,
                movieId = movieId ?: existing?.movieId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun attachMovie(pickcode: String, movieId: Long) = withContext(Dispatchers.IO) {
        val existing = dao.get(pickcode) ?: return@withContext
        dao.upsert(existing.copy(movieId = movieId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateStrmLocation(
        pickcode: String,
        strmUri: String,
        libraryRootUri: String?,
        movieId: Long?
    ) = withContext(Dispatchers.IO) {
        val existing = dao.get(pickcode) ?: return@withContext
        val fileName = DocumentFile.fromSingleUri(context, Uri.parse(strmUri))
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: existing.fileName
        val info = extractMovieNumberInfo(fileName)
        dao.upsert(
            existing.copy(
                fileName = fileName,
                movieNumber = info?.number ?: existing.movieNumber,
                variant = detectMovieVariant(fileName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = strmUri,
                libraryRootUri = libraryRootUri ?: existing.libraryRootUri,
                movieId = movieId ?: existing.movieId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteForMovie(movieId: Long, pickcodes: Set<String>) = withContext(Dispatchers.IO) {
        if (pickcodes.isNotEmpty()) {
            dao.deleteByPickcodes(pickcodes.toList())
        }
        dao.deleteByMovieId(movieId)
    }

    suspend fun getByMovieNumber(movieNumber: String): List<CloudStrmRecordEntity> = withContext(Dispatchers.IO) {
        dao.getByMovieNumber(movieNumber)
    }

    suspend fun findStandardSameNumberCandidate(fileName: String, newPickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        val info = extractMovieNumberInfo(fileName) ?: return@withContext null
        if (info.partLabel != null) return@withContext null
        if (detectMovieVariant(fileName) != MovieVariant.Standard) return@withContext null
        dao.getByMovieNumber(info.number)
            .firstOrNull { record ->
                record.pickcode != newPickcode &&
                    record.partLabel == null &&
                    record.variant.isNullOrBlank()
            }
    }

    suspend fun replacePickcode(
        oldPickcode: String,
        newPickcode: String,
        newVideoName: String
    ): CloudStrmRecordEntity = withContext(Dispatchers.IO) {
        val existing = dao.get(oldPickcode) ?: error("没有找到要替换的旧 STRM 记录")
        val uri = Uri.parse(existing.strmUri)
        val content = "${settingsRepository.getStrmBaseUrl()}/download_m3u/$newPickcode/${Uri.encode(newVideoName)}"
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入旧 STRM 文件，不能替换 pickcode")
        dao.deleteByPickcode(oldPickcode)
        val updated = existing.copy(
            pickcode = newPickcode,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        updated
    }

    suspend fun rebuildIndexAndNormalizeSegments(): CloudStrmIndexResult = withContext(Dispatchers.IO) {
        val rootUris = (settingsRepository.getKnownStrmTreeUris() + settingsRepository.getKnownLibraryRootUris())
            .filter { it.isNotBlank() }
            .distinct()
        if (rootUris.isEmpty()) return@withContext CloudStrmIndexResult()
        val records = mutableListOf<CloudStrmRecordEntity>()
        var renamed = 0
        val now = System.currentTimeMillis()

        fun walk(directory: DocumentFile, rootUri: String) {
            directory.listFiles().forEach { file ->
                if (file.isDirectory) {
                    walk(file, rootUri)
                    return@forEach
                }
                if (!file.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val content = context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                val pickcode = PickcodeExtractor.extract(content) ?: return@forEach
                val info = extractMovieNumberInfo(file.name.orEmpty())
                val normalized = normalizedSegmentName(directory, file.name.orEmpty(), info)
                val targetFile = if (normalized != null && normalized != file.name) {
                    copyTextFile(directory, normalized, content)?.also {
                        if (file.delete()) renamed += 1
                    } ?: file
                } else {
                    file
                }
                records += CloudStrmRecordEntity(
                    pickcode = pickcode,
                    fileName = targetFile.name.orEmpty(),
                    movieNumber = info?.number,
                    variant = detectMovieVariant(targetFile.name.orEmpty()).suffix.takeIf { it.isNotBlank() },
                    partLabel = info?.partLabel,
                    strmUri = targetFile.uri.toString(),
                    libraryRootUri = rootUri,
                    movieId = null,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }

        rootUris.forEach { rootUri ->
            DocumentFile.fromTreeUri(context, Uri.parse(rootUri))?.let { root ->
                walk(root, rootUri)
            }
        }
        records += indexKnownMovieRecords()
        dao.upsertAll(records)
        hasIndexedKnownMovies = true
        CloudStrmIndexResult(indexed = records.size, renamed = renamed)
    }

    private suspend fun ensureKnownMoviesIndexed() {
        if (hasIndexedKnownMovies) return
        val records = indexKnownMovieRecords()
        if (records.isNotEmpty()) {
            dao.upsertAll(records)
        }
        hasIndexedKnownMovies = true
    }

    private fun CloudStrmRecordEntity.strmExists(): Boolean {
        val uri = runCatching { Uri.parse(strmUri) }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private suspend fun indexKnownMovieRecords(): List<CloudStrmRecordEntity> {
        val existing = dao.getAllPickcodes().toSet()
        val now = System.currentTimeMillis()
        return movieDao.getMoviesSnapshotLite()
            .asSequence()
            .filter { it.videoName.endsWith(".strm", ignoreCase = true) || it.videoUri.endsWith(".strm", ignoreCase = true) }
            .mapNotNull { movie -> movie.toCloudStrmRecord(now, existing) }
            .toList()
    }

    private suspend fun indexKnownMovieRecordsForPickcodes(targetPickcodes: Set<String>): List<CloudStrmRecordEntity> {
        if (targetPickcodes.isEmpty()) return emptyList()
        val existing = dao.getAllPickcodes().toSet()
        val found = mutableListOf<CloudStrmRecordEntity>()
        val remaining = targetPickcodes.toMutableSet()
        val now = System.currentTimeMillis()
        movieDao.getMoviesSnapshotLite()
            .asSequence()
            .filter { it.videoName.endsWith(".strm", ignoreCase = true) || it.videoUri.endsWith(".strm", ignoreCase = true) }
            .forEach { movie ->
                if (remaining.isEmpty()) return@forEach
                val record = movie.toCloudStrmRecord(now, existing) ?: return@forEach
                if (record.pickcode in remaining) {
                    found += record
                    remaining -= record.pickcode
                }
            }
        return found
    }

    private fun MovieEntity.toCloudStrmRecord(now: Long, existingPickcodes: Set<String>): CloudStrmRecordEntity? {
        val content = runCatching {
            context.contentResolver.openInputStream(Uri.parse(videoUri))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        val pickcode = PickcodeExtractor.extract(content) ?: return null
        if (pickcode in existingPickcodes) return null
        val info = extractMovieNumberInfo(videoName) ?: extractMovieNumberInfo(title)
        return CloudStrmRecordEntity(
            pickcode = pickcode,
            fileName = videoName,
            movieNumber = info?.number,
            variant = detectMovieVariant(videoName).suffix.takeIf { it.isNotBlank() },
            partLabel = info?.partLabel,
            strmUri = videoUri,
            libraryRootUri = libraryRootUri,
            movieId = id,
            createdAt = scannedAtMillis.takeIf { it > 0 } ?: now,
            updatedAt = now
        )
    }

    private fun normalizedSegmentName(directory: DocumentFile, currentName: String, info: com.example.localmovielibrary.util.MovieNumberInfo?): String? {
        val part = info?.partLabel ?: return null
        val base = directory.name?.takeIf { it.contains(info.number, ignoreCase = true) } ?: info.number
        val desired = "$base${playbackSourceSuffix(part, detectMovieVariant(currentName))}.strm".sanitizeFileName()
        return desired.takeIf { it != currentName && directory.findFile(it) == null }
    }

    private fun copyTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile? {
        val file = directory.createFile("application/octet-stream", fileName) ?: return null
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "video.strm" }
}

data class CloudStrmIndexResult(
    val indexed: Int = 0,
    val renamed: Int = 0
)
