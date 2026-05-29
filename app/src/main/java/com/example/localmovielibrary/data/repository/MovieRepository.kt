package com.example.localmovielibrary.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.CloudStrmRecordDao
import com.example.localmovielibrary.data.local.CloudStrmRecordEntity
import com.example.localmovielibrary.data.local.MovieDao
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.local.MovieLibraryMetadata
import kotlinx.coroutines.flow.map
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.scanner.LibraryScanner
import com.example.localmovielibrary.scanner.NfoParser
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class MovieRepository(
    private val context: Context,
    private val movieDao: MovieDao,
    private val cloudStrmRecordDao: CloudStrmRecordDao,
    private val scanner: LibraryScanner,
    private val contentResolver: ContentResolver
) {
    fun observeMovies(): Flow<List<MovieEntity>> =
        movieDao.observeMovieListItems().map { items -> items.map { it.toMovieEntity() } }

    fun observeMovieLibraryMetadata(): Flow<List<MovieLibraryMetadata>> = movieDao.observeMovieLibraryMetadata()

    fun observeMovie(id: Long): Flow<MovieEntity?> = movieDao.observeMovie(id)

    suspend fun searchMovies(query: String, scope: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        when (scope.lowercase(Locale.ROOT)) {
            "title" -> movieDao.searchMoviesByTitleLite(pattern)
            "actor" -> movieDao.searchMoviesByActorLite(pattern)
            "tag" -> movieDao.searchMoviesByTagLite(pattern)
            "genre" -> movieDao.searchMoviesByGenreLite(pattern)
            else -> movieDao.searchMoviesLite(pattern)
        }
    }

    suspend fun filterMovies(type: String, value: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = value.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        when (type.lowercase(Locale.ROOT)) {
            "actor" -> movieDao.searchMoviesByActorLite(pattern)
            "tag" -> movieDao.searchMoviesByTagLite(pattern)
            "genre" -> movieDao.searchMoviesByGenreLite(pattern)
            "year" -> movieDao.searchMoviesByYearLite(text)
            "studio" -> movieDao.searchMoviesByStudioLite(pattern)
            "collection" -> movieDao.searchMoviesByCollectionLite(pattern)
            else -> emptyList()
        }
    }

    suspend fun scanLibrary(rootUri: Uri): Int {
        return withContext(Dispatchers.IO) {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val movies = scanner.scan(rootUri)
            val existingMovies = movieDao.getMoviesByLibraryRootLite(rootUri.toString())
            val existingByUri = existingMovies.associateBy { it.videoUri }
            val existingById = existingMovies.associateBy { it.id }
            val cloudRecords = cloudStrmRecordDao.getAll()
            val existingByPickcode = buildExistingMoviePickcodeMap(existingMovies, existingById, cloudRecords)
            val existingByNumber = existingMovies.mapNotNull { movie ->
                movie.movieNumberKey()?.let { it to movie }
            }.toMap()
            movieDao.deleteByLibraryRoot(rootUri.toString())
            movieDao.upsertAll(
                movies.map { scanned ->
                    val pickcode = scanned.extractPickcodeFromStrm()
                    val old = pickcode?.let { existingByPickcode[it] }
                        ?: existingByUri[scanned.videoUri]
                        ?: scanned.movieNumberKey()?.let { existingByNumber[it] }
                    if (old == null) {
                        scanned
                    } else {
                        scanned.copy(
                            isFavorite = old.isFavorite,
                            isWatched = old.isWatched,
                            scannedAtMillis = old.scannedAtMillis,
                            updatedAt = old.updatedAt
                        )
                    }
                }
            )
            updateCloudStrmRecordsAfterScan(movies, rootUri.toString(), cloudRecords)
            movies.size
        }
    }

    suspend fun reorganizeExistingLibraries(): LibraryReorganizeResult = withContext(Dispatchers.IO) {
        val rootUris = movieDao.getLibraryRootUris()

        var refreshedMovies = 0
        var movedFolders = 0
        val failedRoots = mutableListOf<String>()

        rootUris.forEach { rootUri ->
            runCatching { reorganizeLibraryByActorFolders(Uri.parse(rootUri)) }
                .onSuccess { result ->
                    refreshedMovies += result.movieCount
                    movedFolders += result.movedFolders
                }
                .onFailure { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                    failedRoots += "$rootUri: $message"
                }
        }

        LibraryReorganizeResult(
            rootCount = rootUris.size,
            movieCount = refreshedMovies,
            movedFolders = movedFolders,
            failedRoots = failedRoots
        )
    }

    suspend fun reorganizeLibraryByActorFolders(rootUri: Uri): LibraryReorganizeResult = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Library root directory is unavailable")
        if (!root.canWrite()) error("Library root directory is not writable")

        var movedFolders = 0
        root.listFiles()
            .filter { it.isDirectory }
            .filter { it.isRootMovieDirectory() }
            .forEach { movieDirectory ->
                val groupName = movieDirectory.actorGroupFolderName()
                val actorDirectory = root.findOrCreateDirectory(groupName)
                if (actorDirectory.uri == movieDirectory.uri) return@forEach
                val targetName = actorDirectory.uniqueChildDirectoryName(movieDirectory.name.orEmpty())
                val copied = copyDirectoryRecursively(movieDirectory, actorDirectory, targetName)
                if (copied != null) {
                    deleteRecursively(movieDirectory)
                    movedFolders += 1
                }
            }

        val count = scanLibrary(rootUri)
        LibraryReorganizeResult(rootCount = 1, movieCount = count, movedFolders = movedFolders)
    }

    suspend fun setFavorite(movieId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setFavorite(movieId, isFavorite, System.currentTimeMillis())
    }

    suspend fun setWatched(movieId: Long, isWatched: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setWatched(movieId, isWatched, System.currentTimeMillis())
    }

    suspend fun deleteMovie(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.deleteById(movieId)
    }

    suspend fun deleteMovieWithFiles(movieId: Long): DeleteMovieResult = withContext(Dispatchers.IO) {
        val movie = movieDao.getMovie(movieId)
        val pickcodes = linkedSetOf<String>()
        if (movie != null && movie.videoName.endsWith(".strm", ignoreCase = true)) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
            val target = root?.let {
                findFileWithParentFast(it, movie.libraryRootUri, movie.videoUri)
                    ?: findFileWithParent(it, movie.videoUri)
            }
            if (target != null) {
                val movieDirectory = target.parent.takeIf { it.uri != root.uri }
                val actorDirectory = movieDirectory
                    ?.parentFile
                    ?.takeIf { it.uri != root.uri }
                val filesToRead = if (target.parent.uri != root.uri) {
                    target.parent.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".strm", ignoreCase = true) }
                } else {
                    listOf(target.file)
                }
                filesToRead.forEach { file ->
                    readPickcode(file)?.let { pickcodes += it }
                }
                if (target.parent.uri != root.uri) {
                    deleteRecursively(target.parent)
                    cleanupEmptyActorDirectory(actorDirectory, root)
                } else {
                    target.file.delete()
                }
            }
        }
        movieDao.deleteById(movieId)
        DeleteMovieResult(movieId = movieId, pickcodes = pickcodes)
    }

    suspend fun refreshMovie(movieId: Long): Boolean = withContext(Dispatchers.IO) {
        val old = movieDao.getMovie(movieId) ?: return@withContext false
        val refreshed = scanner.scanFile(Uri.parse(old.libraryRootUri), Uri.parse(old.videoUri))
            ?: return@withContext false

        movieDao.upsert(
            refreshed.copy(
                id = old.id,
                isFavorite = old.isFavorite,
                isWatched = old.isWatched,
                updatedAt = System.currentTimeMillis()
            )
        )
        true
    }

    suspend fun scanSingleMovie(rootUri: Uri, videoUri: Uri, mergeByMovieNumber: Boolean = true): MovieEntity? = withContext(Dispatchers.IO) {
        val scanned = scanner.scanFile(rootUri, videoUri) ?: return@withContext null
        val pickcode = scanned.extractPickcodeFromStrm()
        val old = pickcode?.let { pick ->
            cloudStrmRecordDao.get(pick)?.movieId?.let { movieDao.getMovie(it) }
        }
            ?: movieDao.getMovieByVideoUri(scanned.videoUri)
            ?: scanned.movieNumberKey()?.takeIf { mergeByMovieNumber }?.let { key ->
                movieDao.getMoviesByLibraryRootLite(rootUri.toString()).firstOrNull { it.movieNumberKey() == key }
            }
        val movie = old?.let {
            scanned.copy(
                id = it.id,
                isFavorite = it.isFavorite,
                isWatched = it.isWatched,
                scannedAtMillis = it.scannedAtMillis,
                updatedAt = it.updatedAt
            )
        } ?: scanned
        movieDao.upsert(movie)
        val saved = movieDao.getMovieByVideoUri(movie.videoUri) ?: movie
        if (pickcode != null) {
            updateCloudStrmRecordLocation(pickcode, saved, rootUri.toString())
        }
        saved
    }

    suspend fun findMovieByNumber(rootUri: String, number: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        movieDao.getMoviesByLibraryRootLite(rootUri)
            .firstOrNull { it.movieNumberKey() == normalized }
    }

    suspend fun findMovieByNumberAndVariant(rootUri: String, number: String, sourceText: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        val expectedKey = normalized + detectMovieVariant(sourceText).suffix
        movieDao.getMoviesByLibraryRootLite(rootUri)
            .firstOrNull { movie -> movie.movieNumberKey() == expectedKey }
    }

    suspend fun getPlaybackParts(movieId: Long): List<MoviePlaybackPart> = withContext(Dispatchers.IO) {
        val movie = movieDao.getMovie(movieId) ?: return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri)) ?: return@withContext movie.singlePart()
        val target = findFileWithParentFast(root, movie.libraryRootUri, movie.videoUri)
            ?: findFileWithParent(root, movie.videoUri)
            ?: return@withContext movie.singlePart()
        val number = movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
            ?: return@withContext movie.singlePart()
        val parts = target.parent.listFiles()
            .filter { it.isFile && it.isSupportedVideoFile() }
            .filter { it.name.orEmpty().movieNumberKeyFromText() == number }
            .map { file ->
                MoviePlaybackPart(
                    label = file.name.orEmpty().playbackPartUiLabel(),
                    videoUri = file.uri.toString(),
                    fileName = file.name.orEmpty()
                )
            }
            .distinctBy { it.videoUri }
            .sortedWith(compareBy<MoviePlaybackPart> { it.label.playbackPartUiSortKey() }.thenBy { it.fileName.lowercase() })
        parts.ifEmpty { movie.singlePart() }
    }

    private fun findFileWithParent(directory: DocumentFile, videoUri: String): FileWithParent? {
        directory.listFiles().forEach { child ->
            if (child.isFile && child.uri.toString() == videoUri) {
                return FileWithParent(parent = directory, file = child)
            }
            if (child.isDirectory) {
                findFileWithParent(child, videoUri)?.let { return it }
            }
        }
        return null
    }

    private fun findFileWithParentFast(root: DocumentFile, rootUriString: String, videoUriString: String): FileWithParent? {
        val rootDocId = Uri.parse(rootUriString).treeDocumentId() ?: return null
        val videoDocId = Uri.parse(videoUriString).documentId() ?: return null
        if (!videoDocId.startsWith(rootDocId)) return null
        val relativePath = videoDocId
            .removePrefix(rootDocId)
            .removePrefix("/")
            .takeIf { it.isNotBlank() }
            ?: return null
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val fileName = segments.last()
        val parent = segments.dropLast(1).fold(root as DocumentFile?) { directory, segment ->
            directory?.findFile(segment)?.takeIf { it.isDirectory }
        } ?: return null
        val file = parent.findFile(fileName)?.takeIf { it.isFile } ?: return null
        return FileWithParent(parent = parent, file = file)
    }

    private fun readPickcode(file: DocumentFile): String? {
        val content = runCatching {
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        return PickcodeExtractor.extract(content)
    }

    private fun MovieEntity.extractPickcodeFromStrm(): String? {
        if (!videoName.endsWith(".strm", ignoreCase = true) && !videoUri.endsWith(".strm", ignoreCase = true)) {
            return null
        }
        val content = runCatching {
            contentResolver.openInputStream(Uri.parse(videoUri))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        return PickcodeExtractor.extract(content)
    }

    private fun buildExistingMoviePickcodeMap(
        existingMovies: List<MovieEntity>,
        existingById: Map<Long, MovieEntity>,
        cloudRecords: List<CloudStrmRecordEntity>
    ): Map<String, MovieEntity> {
        val result = linkedMapOf<String, MovieEntity>()
        cloudRecords.forEach { record ->
            val movie = record.movieId?.let { existingById[it] }
                ?: existingMovies.firstOrNull { it.videoUri == record.strmUri }
                ?: return@forEach
            result[record.pickcode] = movie
        }
        existingMovies.forEach { movie ->
            val pickcode = movie.extractPickcodeFromStrm() ?: return@forEach
            result.putIfAbsent(pickcode, movie)
        }
        return result
    }

    private suspend fun updateCloudStrmRecordsAfterScan(
        scannedMovies: List<MovieEntity>,
        libraryRootUri: String,
        existingRecords: List<CloudStrmRecordEntity>
    ) {
        val existingByPickcode = existingRecords.associateBy { it.pickcode }
        scannedMovies.forEach { scanned ->
            val pickcode = scanned.extractPickcodeFromStrm() ?: return@forEach
            val saved = movieDao.getMovieByVideoUri(scanned.videoUri) ?: return@forEach
            val existing = existingByPickcode[pickcode]
            if (existing == null) {
                val info = extractMovieNumberInfo(saved.videoName) ?: extractMovieNumberInfo(saved.title)
                cloudStrmRecordDao.upsert(
                    CloudStrmRecordEntity(
                        pickcode = pickcode,
                        fileName = saved.videoName,
                        movieNumber = info?.number,
                        variant = detectMovieVariant(saved.videoName).suffix.takeIf { it.isNotBlank() },
                        partLabel = info?.partLabel,
                        strmUri = saved.videoUri,
                        libraryRootUri = libraryRootUri,
                        movieId = saved.id,
                        createdAt = saved.scannedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else if (existing.strmUri != saved.videoUri || existing.movieId != saved.id || existing.libraryRootUri != libraryRootUri) {
                cloudStrmRecordDao.upsert(
                    existing.copy(
                        fileName = saved.videoName,
                        strmUri = saved.videoUri,
                        libraryRootUri = libraryRootUri,
                        movieId = saved.id,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun updateCloudStrmRecordLocation(pickcode: String, movie: MovieEntity, libraryRootUri: String) {
        val existing = cloudStrmRecordDao.get(pickcode)
        val info = extractMovieNumberInfo(movie.videoName) ?: extractMovieNumberInfo(movie.title)
        val now = System.currentTimeMillis()
        cloudStrmRecordDao.upsert(
            (existing ?: CloudStrmRecordEntity(
                pickcode = pickcode,
                fileName = movie.videoName,
                movieNumber = info?.number,
                variant = detectMovieVariant(movie.videoName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = movie.videoUri,
                libraryRootUri = libraryRootUri,
                movieId = movie.id,
                createdAt = movie.scannedAtMillis.takeIf { it > 0 } ?: now,
                updatedAt = now
            )).copy(
                fileName = movie.videoName,
                movieNumber = info?.number ?: existing?.movieNumber,
                variant = detectMovieVariant(movie.videoName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = movie.videoUri,
                libraryRootUri = libraryRootUri,
                movieId = movie.id,
                updatedAt = now
            )
        )
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun cleanupEmptyActorDirectory(actorDirectory: DocumentFile?, root: DocumentFile) {
        if (actorDirectory == null) return
        if (actorDirectory.uri == root.uri) return
        if (!actorDirectory.isDirectory) return
        if (!actorDirectory.isAutoOrganizedActorDirectory()) return
        if (actorDirectory.listFiles().isEmpty()) {
            actorDirectory.delete()
        }
    }

    private fun DocumentFile.isAutoOrganizedActorDirectory(): Boolean {
        val name = name.orEmpty().trim()
        if (name.isBlank()) return false
        if (name == "\u591A\u4EBA\u4F5C\u54C1" || name == "\u672A\u77E5\u6F14\u5458") return true
        return listFiles().isEmpty() && !name.contains(Regex("""[\\/:*?"<>|]"""))
    }

    private fun DocumentFile.isRootMovieDirectory(): Boolean {
        val name = name.orEmpty()
        if (!name.startsWith("\u3010") && !name.startsWith("[")) return false
        if (name.movieNumberKeyFromText() == null) return false
        return listFiles().any { child ->
            child.isFile && (child.isSupportedVideoFile() || child.name.orEmpty().endsWith(".nfo", ignoreCase = true))
        }
    }

    private fun DocumentFile.actorGroupFolderName(): String {
        val actorsFromNfo = firstNfoFile()
            ?.let { NfoParser(contentResolver).parse(it.uri).actors }
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (actorsFromNfo.size > 1) return "\u591A\u4EBA\u4F5C\u54C1"
        if (actorsFromNfo.size == 1) return actorsFromNfo.first().sanitizeDocumentName()

        val actorFromFolder = name.orEmpty().extractBracketActorName()
        return actorFromFolder?.sanitizeDocumentName()?.takeIf { it.isNotBlank() } ?: "\u672A\u77E5\u6F14\u5458"
    }

    private fun DocumentFile.firstNfoFile(): DocumentFile? =
        listFiles().firstOrNull { it.isFile && it.name.orEmpty().endsWith(".nfo", ignoreCase = true) }

    private fun DocumentFile.findOrCreateDirectory(name: String): DocumentFile {
        findFile(name)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        return createDirectory(name) ?: error("Unable to create directory: $name")
    }

    private fun DocumentFile.uniqueChildDirectoryName(baseName: String): String {
        val safeBaseName = baseName.sanitizeDocumentName().ifBlank { "movie" }
        if (findFile(safeBaseName) == null) return safeBaseName
        var index = 1
        while (true) {
            val candidate = "$safeBaseName-$index"
            if (findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun copyDirectoryRecursively(source: DocumentFile, targetParent: DocumentFile, targetName: String): DocumentFile? {
        val target = targetParent.createDirectory(targetName) ?: return null
        var success = true
        source.listFiles().forEach { child ->
            success = if (child.isDirectory) {
                copyDirectoryRecursively(child, target, child.name.orEmpty().sanitizeDocumentName()) != null && success
            } else {
                copyFile(child, target) && success
            }
        }
        if (!success) {
            deleteRecursively(target)
            return null
        }
        return target
    }

    private fun copyFile(source: DocumentFile, targetParent: DocumentFile): Boolean {
        val fileName = source.name.orEmpty().takeIf { it.isNotBlank() } ?: return false
        val target = targetParent.createFile("application/octet-stream", fileName) ?: return false
        return runCatching {
            contentResolver.openInputStream(source.uri)?.use { input ->
                contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        }.getOrDefault(false)
    }
}

data class MoviePlaybackPart(
    val label: String,
    val videoUri: String,
    val fileName: String
)

data class DeleteMovieResult(
    val movieId: Long,
    val pickcodes: Set<String>
)

private data class FileWithParent(
    val parent: DocumentFile,
    val file: DocumentFile
)

private fun MovieEntity.singlePart(): List<MoviePlaybackPart> =
    listOf(MoviePlaybackPart(label = videoName.playbackPartUiLabel(), videoUri = videoUri, fileName = videoName))

data class LibraryReorganizeResult(
    val rootCount: Int,
    val movieCount: Int,
    val movedFolders: Int = 0,
    val failedRoots: List<String> = emptyList()
) {
    val hasFailures: Boolean
        get() = failedRoots.isNotEmpty()
}

private fun MovieEntity.movieNumberKey(): String? {
    val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
        .joinToString(" ")
    val number = source.movieNumberKeyFromText() ?: return null
    return number + detectMovieVariant(source).suffix
}

private fun String.movieNumberKeyFromText(): String? {
    val match = Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})\b""").findAll(this.substringBeforeLast('.', this)).lastOrNull() ?: return null
    return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
}

private fun String.escapeLikePattern(): String =
    replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

private fun String.extractBracketActorName(): String? {
    val match = Regex("""^[\u3010\[]([^\u3011\]]+)[\u3011\]]""").find(this) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun String.sanitizeDocumentName(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").trim()

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

private fun String.segmentPartLabel(): String? {
    extractMovieNumberInfo(this)?.partLabel?.let { return it }
    val baseName = substringBeforeLast('.', this)
    Regex("""(?i)(?:^|[._ -])part\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(baseName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "P$it" }
    Regex("""(?i)(?:^|[._ -])p\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(baseName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "P$it" }
    val match = Regex("""(?i)\b[a-z]{2,10}[-_ ]?\d{2,6}[-_ ]([a-z])(?:$|[^a-z0-9])""")
        .find(baseName)
        ?: return null
    return match.groupValues[1].uppercase(Locale.ROOT)
}

private fun String.playbackPartLabel(): String {
    if (detectMovieVariant(this) == MovieVariant.FourK) return "4K"
    return segmentPartLabel() ?: "正片"
}

private fun String.playbackPartSortKey(): Int =
    when {
        this == "正片" -> 0
        this == "4K" -> 50
        length == 1 && first() in 'A'..'Z' -> 10 + first().code - 'A'.code
        matches(Regex("""P\d{1,2}""")) -> 100 + (drop(1).toIntOrNull() ?: 99)
        else -> Int.MAX_VALUE
    }

private fun String.playbackPartDisplayLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .suffix
        .removePrefix("-")
        .takeIf { it.isNotBlank() }
    return when {
        part != null && variant != null -> "$part $variant"
        part != null -> part
        variant != null -> variant
        else -> "姝ｇ墖"
    }
}

private fun String.playbackPartDisplaySortKey(): Int {
    val partToken = substringBefore(' ')
    val partScore = when {
        this == "姝ｇ墖" -> 0
        partToken == "4K" || partToken == "8K" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 2
        contains("4K") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}

private fun String.playbackPartUiLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .suffix
        .removePrefix("-")
        .takeIf { it.isNotBlank() }
    return when {
        part != null && variant != null -> "$part $variant"
        part != null -> part
        variant != null -> variant
        else -> "\u6B63\u7247"
    }
}

private fun String.playbackPartUiSortKey(): Int {
    val partToken = substringBefore(' ')
    val partScore = when {
        this == "\u6B63\u7247" -> 0
        partToken == "4K" || partToken == "8K" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 2
        contains("4K") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}

private fun DocumentFile.isSupportedVideoFile(): Boolean {
    val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "strm", "ts", "iso", "flv")
}
