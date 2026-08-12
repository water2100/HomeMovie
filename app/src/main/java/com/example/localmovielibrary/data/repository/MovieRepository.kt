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
import com.example.localmovielibrary.data.local.MovieListItem
import com.example.localmovielibrary.data.local.MoviePlaybackKeyItem
import com.example.localmovielibrary.data.local.ScrapeTaskStatus
import kotlinx.coroutines.flow.map
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.scanner.LibraryScanner
import com.example.localmovielibrary.scraper.ScrapeLogStore
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.containsMetadataValue
import com.example.localmovielibrary.util.metadataKey
import com.example.localmovielibrary.util.movieKeyFromText
import com.example.localmovielibrary.util.movieVersionKeyFromText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MovieRepository(
    private val context: Context,
    private val movieDao: MovieDao,
    private val cloudStrmRecordDao: CloudStrmRecordDao,
    private val scanner: LibraryScanner,
    private val contentResolver: ContentResolver,
    private val storedDocumentLocator: StoredDocumentLocator = StoredDocumentLocator(context),
    private val logStore: ScrapeLogStore = ScrapeLogStore(context)
) {
    fun observeMovies(): Flow<List<MovieEntity>> =
        movieDao.observeMovieListInvalidation()
            .map { loadMovieListItems(favoritesOnly = false) }
            .distinctUntilChanged()

    fun observeFavoriteMovies(): Flow<List<MovieEntity>> =
        movieDao.observeFavoriteMovieListInvalidation()
            .map { loadMovieListItems(favoritesOnly = true) }
            .distinctUntilChanged()

    fun observeMoviePlaybackKeys(): Flow<List<MoviePlaybackKeyItem>> =
        movieDao.observeMoviePlaybackKeyInvalidation()
            .map { loadMoviePlaybackKeys() }
            .distinctUntilChanged()

    suspend fun getLibrarySummaries(): MovieLibrarySummaries = withContext(Dispatchers.IO) {
        MovieLibrarySummaries(
            collections = summarizeTexts(movieDao.getSeriesMetadataTexts().mapNotNull { it.value }),
            actors = summarizeValues(movieDao.getActorMetadataLists().flatMap { it.items }),
            tags = summarizeValues(movieDao.getTagMetadataLists().flatMap { it.items }),
            genres = summarizeValues(movieDao.getGenreMetadataLists().flatMap { it.items }),
            studios = summarizeValues(movieDao.getStudioMetadataLists().flatMap { it.items })
        )
    }

    suspend fun getMoviesForActorAvatarUpdate(): List<MovieEntity> = withContext(Dispatchers.IO) {
        movieDao.getMoviesForMetadataLookupLite()
    }

    private suspend fun loadMovieListItems(favoritesOnly: Boolean): List<MovieEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MovieListItem>()
        var offset = 0
        while (true) {
            val page = if (favoritesOnly) {
                movieDao.getFavoriteMovieListItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            } else {
                movieDao.getMovieListItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            }
            if (page.isEmpty()) break
            result += page
            if (page.size < MOVIE_LIST_PAGE_SIZE) break
            offset += MOVIE_LIST_PAGE_SIZE
        }
        result.map { it.toMovieEntity() }
    }

    private suspend fun loadMoviePlaybackKeys(): List<MoviePlaybackKeyItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MoviePlaybackKeyItem>()
        var offset = 0
        while (true) {
            val page = movieDao.getMoviePlaybackKeyItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            result += page
            // 一部影片可能从详情页选择了其它分集/版本播放；这些 STRM 地址
            // 与 movies.videoUri 不同，也必须能回查到同一部影片。
            val movieIds = page.map { it.id }
            result += cloudStrmRecordDao.getByMovieIds(movieIds)
                .mapNotNull { record ->
                    record.movieId
                        ?.takeIf { record.strmUri.isNotBlank() }
                        ?.let { movieId -> MoviePlaybackKeyItem(id = movieId, videoUri = record.strmUri) }
                }
            if (page.size < MOVIE_LIST_PAGE_SIZE) break
            offset += MOVIE_LIST_PAGE_SIZE
        }
        result.distinct()
    }

    fun observeMovie(id: Long): Flow<MovieEntity?> = movieDao.observeMovie(id)

    suspend fun findSimilarMovies(current: MovieEntity, limit: Int = 12): List<MovieEntity> = withContext(Dispatchers.IO) {
        val currentCode = current.similarCodeInfo()
        val currentActors = current.actors.map { it.similarNormalized() }.filter { it.isNotBlank() }.toSet()
        if (currentCode == null && currentActors.isEmpty()) return@withContext emptyList()

        val candidates = similarCandidates(current, currentCode, currentActors)
        candidates
            .asSequence()
            .filter { it.id != current.id }
            .map { movie ->
                val movieCode = movie.similarCodeInfo()
                val sameActorScore = movie.actors.count { it.similarNormalized() in currentActors }
                val samePrefix = currentCode != null && movieCode?.prefix == currentCode.prefix
                val distance = if (currentCode != null && movieCode != null && samePrefix) {
                    abs(movieCode.number - currentCode.number)
                } else {
                    Int.MAX_VALUE
                }
                val score = sameActorScore * 1000 + if (samePrefix) 250 else 0
                movie to SimilarMovieRank(score = score, distance = distance)
            }
            .filter { (_, rank) -> rank.score > 0 }
            .sortedWith(
                compareByDescending<Pair<MovieEntity, SimilarMovieRank>> { it.second.score }
                    .thenBy { it.second.distance }
                    .thenBy { pair -> pair.first.sortTitle.ifBlank { pair.first.title }.lowercase(Locale.ROOT) }
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    private suspend fun similarCandidates(
        current: MovieEntity,
        currentCode: SimilarCodeInfo?,
        currentActors: Set<String>
    ): List<MovieEntity> {
        val candidates = linkedMapOf<Long, MovieEntity>()
        currentActors
            .take(SIMILAR_ACTOR_PREFILTER_LIMIT)
            .forEach { actor ->
                movieDao.getSimilarCandidatesByActorLite("%${actor.escapeLikePattern()}%")
                    .forEach { candidates[it.id] = it }
            }
        currentCode?.let { code ->
            movieDao.getSimilarCandidatesByCodeLite("%${code.prefix.lowercase(Locale.ROOT)}%")
                .forEach { candidates[it.id] = it }
            movieDao.getSimilarCandidatesByCodeLite("%${code.prefix.uppercase(Locale.ROOT)}%")
                .forEach { candidates[it.id] = it }
        }
        return candidates.values.toList()
    }

    suspend fun searchMovies(query: String, scope: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        when (scope.lowercase(Locale.ROOT)) {
            "title" -> movieDao.searchMoviesByTitleLite(pattern)
            "number" -> movieDao.searchMoviesByNumberLite(text.movieNumberCandidateLikePattern())
            "actor" -> filterMetadataMovies(movieDao.getMoviesForActorLookupLite(pattern), text, exact = false) { it.actors }
            "tag" -> filterMetadataMovies(movieDao.getMoviesForTagLookupLite(pattern), text, exact = false) { it.tags }
            "genre" -> filterMetadataMovies(movieDao.getMoviesForGenreLookupLite(pattern), text, exact = false) { it.genres }
            else -> movieDao.searchMoviesLite(pattern)
        }
    }

    suspend fun filterMovies(type: String, value: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = value.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        when (type.lowercase(Locale.ROOT)) {
            "actor" -> filterMetadataMovies(movieDao.getMoviesForActorLookupLite(pattern), text, exact = true) { it.actors }
            "tag" -> filterMetadataMovies(movieDao.getMoviesForTagLookupLite(pattern), text, exact = true) { it.tags }
            "genre" -> filterMetadataMovies(movieDao.getMoviesForGenreLookupLite(pattern), text, exact = true) { it.genres }
            "year" -> movieDao.searchMoviesByYearLite(text)
            "studio" -> filterMetadataMovies(movieDao.getMoviesForStudioLookupLite(pattern), text, exact = true) { it.studios }
            "collection" -> filterCollectionMovies(text, exact = true)
            else -> emptyList()
        }
    }

    fun observeFilteredMovies(type: String, value: String): Flow<List<MovieEntity>> =
        movieDao.observeMovieListInvalidation()
            .map { filterMovies(type, value) }
            .distinctUntilChanged()

    private suspend fun filterMetadataMovies(
        candidates: List<MovieEntity>,
        value: String,
        exact: Boolean,
        values: (MovieEntity) -> List<String>
    ): List<MovieEntity> {
        return candidates
            .filter { movie -> values(movie).containsMetadataValue(value, exact) }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    private suspend fun filterCollectionMovies(value: String, exact: Boolean): List<MovieEntity> {
        val query = value.metadataKey()
        if (query.isBlank()) return emptyList()
        val pattern = "%${value.escapeLikePattern()}%"
        return movieDao.getMoviesForCollectionLookupLite(pattern)
            .filter { movie ->
                val candidate = movie.series.orEmpty().metadataKey()
                candidate.isNotBlank() && if (exact) candidate == query else candidate.contains(query)
            }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    suspend fun scanLibrary(rootUri: Uri): Int {
        return withContext(Dispatchers.IO) {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val movies = scanner.scan(rootUri)
            val existingMovies = movieDao.getMoviesByLibraryRootLite(rootUri.toString())
            val allMovies = movieDao.getMoviesSnapshotLite()
            val existingByUri = allMovies.associateBy { it.videoUri }
            val existingById = allMovies.associateBy { it.id }
            val existingMovieIds = allMovies.map { it.id }.filter { it > 0 }
            val cloudRecords = (
                cloudStrmRecordDao.getByLibraryRoot(rootUri.toString()) +
                    existingMovieIds.takeIf { it.isNotEmpty() }?.let { cloudStrmRecordDao.getByMovieIds(it) }.orEmpty()
                ).distinctBy { it.pickcode }
            val existingByPickcode = buildExistingMoviePickcodeMap(allMovies, existingById, cloudRecords)
            val existingByNumber = buildExistingMovieNumberMap(allMovies, rootUri.toString())
            val matchedExistingIds = linkedSetOf<Long>()
            val synchronizedMovies = movies.map { scanned ->
                val pickcode = scanned.extractPickcodeFromStrm()
                val old = pickcode?.let { existingByPickcode[it] }
                    ?: existingByUri[scanned.videoUri]
                    ?: scanned.movieNumberKey()?.let { existingByNumber[it] }
                if (old == null) {
                    scanned
                } else {
                    matchedExistingIds += old.id
                    scanned.copy(
                        id = old.id,
                        isFavorite = old.isFavorite,
                        isWatched = old.isWatched,
                        scannedAtMillis = old.scannedAtMillis,
                        updatedAt = old.updatedAt,
                        scrapeFailureReason = scanned.resolvedScrapeFailureReason(old),
                        scrapeTaskStatus = scanned.resolvedScrapeTaskStatus(old)
                    )
                }
            }
            val removedCurrentRootIds = existingMovies
                .asSequence()
                .map { it.id }
                .filter { it !in matchedExistingIds }
                .toList()
            val staleMovedDuplicateIds = findStaleMovedDuplicateIds(
                allMovies = allMovies,
                currentLibraryRootUri = rootUri.toString(),
                scannedMovies = synchronizedMovies,
                cloudRecords = cloudRecords,
                protectedIds = matchedExistingIds + removedCurrentRootIds
            )
            val removedIds = (removedCurrentRootIds + staleMovedDuplicateIds).distinct()
            movieDao.synchronizeLibraryMovies(removedIds, synchronizedMovies)
            if (removedIds.isNotEmpty()) {
                cloudStrmRecordDao.deleteByMovieIds(removedIds)
            }
            updateCloudStrmRecordsAfterScan(movies, rootUri.toString(), cloudRecords)
            movies.size
        }
    }

    private fun buildExistingMovieNumberMap(
        movies: List<MovieEntity>,
        currentLibraryRootUri: String
    ): Map<String, MovieEntity> {
        val result = linkedMapOf<String, MovieEntity>()
        movies
            .sortedBy { movie -> if (movie.libraryRootUri == currentLibraryRootUri) 0 else 1 }
            .forEach { movie ->
                movie.movieNumberKey()?.let { key ->
                    result.putIfAbsent(key, movie)
                }
            }
        return result
    }

    private fun findStaleMovedDuplicateIds(
        allMovies: List<MovieEntity>,
        currentLibraryRootUri: String,
        scannedMovies: List<MovieEntity>,
        cloudRecords: List<CloudStrmRecordEntity>,
        protectedIds: Set<Long>
    ): List<Long> {
        val scannedKeys = scannedMovies.mapNotNull { it.movieNumberKey() }.toSet()
        val scannedPickcodes = scannedMovies.mapNotNull { it.extractPickcodeFromStrm() }.toSet()
        val pickcodeByMovieId = cloudRecords
            .mapNotNull { record -> record.movieId?.let { it to record.pickcode } }
            .groupBy({ it.first }, { it.second })
        return allMovies
            .asSequence()
            .filter { it.libraryRootUri != currentLibraryRootUri }
            .filter { it.id !in protectedIds }
            .filter { movie ->
                val sameNumber = movie.movieNumberKey()?.let { it in scannedKeys } == true
                val samePickcode = pickcodeByMovieId[movie.id]?.any { it in scannedPickcodes } == true
                (samePickcode || sameNumber) && !canOpenUri(movie.videoUri)
            }
            .map { it.id }
            .toList()
    }

    suspend fun setFavorite(movieId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setFavorite(movieId, isFavorite, System.currentTimeMillis())
    }

    suspend fun setWatched(movieId: Long, isWatched: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setWatched(movieId, isWatched, System.currentTimeMillis())
    }

    suspend fun addCustomTag(movieId: Long, tag: String): AddCustomTagResult = withContext(Dispatchers.IO) {
        val normalized = tag
            .replace('\u001F', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return@withContext AddCustomTagResult.Blank
        val currentTags = movieDao.getTags(movieId) ?: return@withContext AddCustomTagResult.MovieNotFound
        if (currentTags.any { it.equals(normalized, ignoreCase = true) }) {
            return@withContext AddCustomTagResult.AlreadyExists
        }
        movieDao.setTags(movieId, currentTags + normalized, System.currentTimeMillis())
        AddCustomTagResult.Added(normalized)
    }

    suspend fun removeCustomTag(movieId: Long, tag: String): RemoveCustomTagResult = withContext(Dispatchers.IO) {
        val normalized = tag.trim()
        if (normalized.isBlank()) return@withContext RemoveCustomTagResult.Blank
        val currentTags = movieDao.getTags(movieId) ?: return@withContext RemoveCustomTagResult.MovieNotFound
        val removedTag = currentTags.firstOrNull { it.equals(normalized, ignoreCase = true) }
            ?: return@withContext RemoveCustomTagResult.NotFound
        movieDao.setTags(movieId, currentTags.filterNot { it.equals(normalized, ignoreCase = true) }, System.currentTimeMillis())
        RemoveCustomTagResult.Removed(removedTag)
    }

    suspend fun deleteMovie(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.deleteById(movieId)
    }

    suspend fun deleteMovieWithFiles(movieId: Long): DeleteMovieResult = withContext(Dispatchers.IO) {
        val movie = movieDao.getMovieLite(movieId)
        val pickcodes = linkedSetOf<String>()
        val strmUrisToClear = linkedSetOf<String>()
        val movieIdsToClear = linkedSetOf(movieId)
        if (movie != null) {
            val relatedRecords = relatedCloudStrmRecords(movie)
            pickcodes += relatedRecords.map { it.pickcode }
            strmUrisToClear += relatedRecords.map { it.strmUri }.filter { it.isNotBlank() }
            movieIdsToClear += relatedRecords.mapNotNull { it.movieId }
            strmUrisToClear += movie.videoUri

            runCatching {
                if (movie.videoName.endsWith(".strm", ignoreCase = true)) {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
                    if (root != null) {
                        val directFiles = linkedMapOf<String, DocumentFile>()
                        val targets = buildList {
                            storedDocumentLocator.find(movie.libraryRootUri, movie.videoUri)?.let { add(it) }
                            DocumentFile.fromSingleUri(context, Uri.parse(movie.videoUri))
                                ?.takeIf { it.isFile }
                                ?.let { directFiles[it.uri.toString()] = it }
                            relatedRecords.forEach { record ->
                                val recordRootUri = record.libraryRootUri ?: movie.libraryRootUri
                                storedDocumentLocator.find(recordRootUri, record.strmUri)?.let { add(it) }
                                DocumentFile.fromSingleUri(context, Uri.parse(record.strmUri))
                                    ?.takeIf { it.isFile }
                                    ?.let { directFiles[it.uri.toString()] = it }
                            }
                        }.distinctBy { it.file.uri.toString() }

                        val movieDirectories = linkedMapOf<String, LocatedDocument>()
                        val rootFiles = linkedMapOf<String, LocatedDocument>()
                        targets.forEach { target ->
                            val filesToRead = if (target.directory.uri != root.uri) {
                                target.directory.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".strm", ignoreCase = true) }
                            } else {
                                listOf(target.file)
                            }
                            filesToRead.forEach { file ->
                                readPickcode(file)?.let { pickcodes += it }
                                strmUrisToClear += file.uri.toString()
                            }
                            if (target.directory.uri != root.uri) {
                                movieDirectories[target.directory.uri.toString()] = target
                            } else {
                                rootFiles[target.file.uri.toString()] = target
                            }
                        }

                        movieDirectories.values.forEach { target ->
                            val actorDirectory = target.parentDirectory?.takeIf { it.uri != root.uri }
                            deleteRecursively(target.directory)
                            cleanupEmptyActorDirectory(actorDirectory, root)
                        }
                        rootFiles.values.forEach { it.file.delete() }
                        directFiles.values.forEach { it.delete() }
                    }
                }
            }
        }
        if (pickcodes.isNotEmpty()) {
            val indexedRecords = cloudStrmRecordDao.getExistingRecords(pickcodes.toList())
            strmUrisToClear += indexedRecords.map { it.strmUri }.filter { it.isNotBlank() }
            movieIdsToClear += indexedRecords.mapNotNull { it.movieId }
        }
        if (strmUrisToClear.isNotEmpty()) {
            movieIdsToClear += movieDao.getMoviesByVideoUrisLite(strmUrisToClear.toList()).map { it.id }
        }
        if (pickcodes.isNotEmpty()) {
            cloudStrmRecordDao.deleteByPickcodes(pickcodes.toList())
        }
        if (strmUrisToClear.isNotEmpty()) {
            cloudStrmRecordDao.deleteByStrmUris(strmUrisToClear.toList())
        }
        cloudStrmRecordDao.deleteByMovieIds(movieIdsToClear.toList())
        movieDao.deleteByIds(movieIdsToClear.toList())
        DeleteMovieResult(movieId = movieId, pickcodes = pickcodes)
    }

    suspend fun renameMovieStrmFile(movieId: Long, newFileName: String): RenameMovieFileResult = withContext(Dispatchers.IO + NonCancellable) {
        val old = movieDao.getMovieLite(movieId) ?: error("影片记录不存在")
        if (!old.videoName.endsWith(".strm", ignoreCase = true)) {
            error("当前影片不是 STRM 文件，暂不支持重命名")
        }
        val normalizedFileName = normalizeStrmFileName(newFileName)
        val rootUri = Uri.parse(old.libraryRootUri)
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("影片库目录不可用")
        if (!root.canWrite()) error("影片库目录没有写入权限")
        val indexedRecords = cloudStrmRecordDao.getByMovieId(old.id)
        val indexedPickcodes = indexedRecords.map { it.pickcode }.filter { it.isNotBlank() }.toSet()
        val target = try {
            requireStoredDocument(
                movieId = old.id,
                fileName = old.videoName,
                databaseUri = old.videoUri,
                operation = "STRM 重命名"
            ) {
                storedDocumentLocator.find(old.libraryRootUri, old.videoUri)
                    ?: indexedRecords.firstNotNullOfOrNull { record ->
                        storedDocumentLocator.find(record.libraryRootUri ?: old.libraryRootUri, record.strmUri)
                    }
                }
        } catch (error: StoredMediaPathException) {
            logStore.appendMediaPath(
                operation = error.operation,
                status = "失败",
                movieId = error.movieId,
                fileName = error.fileName,
                databaseUri = error.databaseUri,
                detail = "文件不存在、被外部移动或权限失效；未自动扫描媒体库"
            )
            throw error
        }

        val oldName = target.file.name.orEmpty()
        if (oldName.equals(normalizedFileName, ignoreCase = false)) {
            return@withContext RenameMovieFileResult(movie = old, oldFileName = oldName, newFileName = oldName)
        }
        val existing = target.directory.findFile(normalizedFileName)
        if (existing != null && existing.uri != target.file.uri) {
            error("同目录已存在：$normalizedFileName")
        }
        val strmContent = contentResolver.openInputStream(target.file.uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("无法读取当前 STRM 文件")
        val pickcodes = (indexedPickcodes + listOfNotNull(PickcodeExtractor.extract(strmContent)))
            .filter { it.isNotBlank() }
            .toSet()
        val renamedFile = copyStrmTextFile(target.directory, normalizedFileName, strmContent)
        val refreshed = scanner.scanFile(rootUri, renamedFile.uri)
            ?: error("重命名成功，但重新扫描 STRM 失败")
        val movie = refreshed.copy(
            id = old.id,
            isFavorite = old.isFavorite,
            isWatched = old.isWatched,
            scannedAtMillis = old.scannedAtMillis,
            updatedAt = System.currentTimeMillis(),
            scrapeFailureReason = null,
            scrapeTaskStatus = ScrapeTaskStatus.None.name
        )
        movieDao.upsert(movie)
        pickcodes.forEach { pickcode ->
            updateCloudStrmRecordLocation(pickcode, movie, movie.libraryRootUri)
        }
        if (target.file.uri != renamedFile.uri) {
            runCatching { target.file.delete() }
        }
        logStore.appendMediaPath(
            operation = "STRM 重命名并更新数据库",
            status = "成功",
            movieId = movie.id,
            fileName = movie.videoName,
            databaseUri = movie.videoUri,
            detail = "影片表和 STRM 索引表已同步"
        )
        RenameMovieFileResult(movie = movie, oldFileName = oldName, newFileName = movie.videoName)
    }

    suspend fun refreshMovie(movieId: Long): Boolean = withContext(Dispatchers.IO) {
        val old = movieDao.getMovieLite(movieId) ?: return@withContext false
        val rootUri = Uri.parse(old.libraryRootUri)
        val refreshed = scanner.scanFile(rootUri, Uri.parse(old.videoUri))
            ?: return@withContext false

        movieDao.upsert(
            refreshed.copy(
                id = old.id,
                isFavorite = old.isFavorite,
                isWatched = old.isWatched,
                updatedAt = System.currentTimeMillis(),
                scrapeFailureReason = refreshed.resolvedScrapeFailureReason(old),
                scrapeTaskStatus = refreshed.resolvedScrapeTaskStatus(old)
            )
        )
        true
    }

    suspend fun refreshMovieAtStoredPath(movieId: Long): MovieEntity? = withContext(Dispatchers.IO) {
        val old = movieDao.getMovieLite(movieId) ?: return@withContext null
        val rootUri = Uri.parse(old.libraryRootUri)
        val refreshed = scanner.scanFile(rootUri, Uri.parse(old.videoUri))
            ?: return@withContext null

        val movie = refreshed.copy(
            id = old.id,
            isFavorite = old.isFavorite,
            isWatched = old.isWatched,
            scannedAtMillis = old.scannedAtMillis,
            updatedAt = System.currentTimeMillis(),
            scrapeFailureReason = refreshed.resolvedScrapeFailureReason(old),
            scrapeTaskStatus = refreshed.resolvedScrapeTaskStatus(old)
        )
        movieDao.upsert(movie)
        movie
    }

    suspend fun scanSingleMovie(
        rootUri: Uri,
        videoUri: Uri,
        mergeByMovieNumber: Boolean = true,
        excludedMergeMovieId: Long? = null
    ): MovieEntity? = withContext(Dispatchers.IO) {
        val scanned = scanner.scanFile(rootUri, videoUri) ?: return@withContext null
        val pickcode = scanned.extractPickcodeFromStrm()
        val oldByVideoUri = movieDao.getMovieByVideoUriLite(scanned.videoUri)
        val oldByNumber = scanned.movieMergeKey()?.takeIf { mergeByMovieNumber }?.let { key ->
                movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri.toString(), key.movieNumberCandidateLikePattern())
                    .firstOrNull { it.movieMergeKey() == key && it.id != excludedMergeMovieId }
            }
        val oldByPickcode = pickcode?.let { pick ->
                cloudStrmRecordDao.get(pick)?.movieId?.let { movieDao.getMovieLite(it) }
            }
        val old = oldByVideoUri ?: oldByNumber ?: oldByPickcode
        val mergedAsAdditionalSource = oldByVideoUri == null &&
            oldByNumber != null &&
            oldByNumber.videoUri != scanned.videoUri
        val movie = old?.let {
            val merged = scanned.copy(
                id = it.id,
                isFavorite = it.isFavorite,
                isWatched = it.isWatched,
                scannedAtMillis = it.scannedAtMillis,
                updatedAt = it.updatedAt,
                scrapeFailureReason = scanned.resolvedScrapeFailureReason(it),
                scrapeTaskStatus = scanned.resolvedScrapeTaskStatus(it)
            )
            if (mergedAsAdditionalSource) {
                merged.copy(
                    libraryRootUri = it.libraryRootUri,
                    videoUri = it.videoUri,
                    videoName = it.videoName
                )
            } else {
                merged
            }
        } ?: scanned
        movieDao.upsert(movie)
        val saved = movieDao.getMovieByVideoUriLite(movie.videoUri) ?: movie
        if (pickcode != null) {
            val sourceRecordMovie = saved.copy(
                libraryRootUri = rootUri.toString(),
                videoUri = scanned.videoUri,
                videoName = scanned.videoName
            )
            updateCloudStrmRecordLocation(pickcode, sourceRecordMovie, rootUri.toString())
        }
        saved
    }

    suspend fun setScrapeFailureReason(movieId: Long, reason: String?) = withContext(Dispatchers.IO) {
        movieDao.setScrapeFailureReason(movieId, reason?.trim()?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
    }

    fun observeUnfinishedScrapeTaskCount(): Flow<Int> =
        movieDao.observeScrapeIssueCount(ScrapeTaskStatus.unfinishedNames)
            .distinctUntilChanged()

    suspend fun scrapeTaskSummary(): ScrapeTaskSummary = withContext(Dispatchers.IO) {
        ScrapeTaskSummary(
            unscraped = movieDao.countUntrackedMoviesWithoutNfo(ScrapeTaskStatus.unfinishedNames),
            pending = movieDao.countScrapeTasks(ScrapeTaskStatus.Pending.name),
            running = movieDao.countScrapeTasks(ScrapeTaskStatus.Running.name),
            failed = movieDao.countScrapeTasks(ScrapeTaskStatus.Failed.name),
            completed = movieDao.countScrapeTasks(ScrapeTaskStatus.Completed.name)
        )
    }

    suspend fun getManualScrapeTaskMovies(): List<MovieEntity> = withContext(Dispatchers.IO) {
        movieDao.getScrapeIssueMovies(ScrapeTaskStatus.unfinishedNames)
    }

    suspend fun getScrapeIssueMovies(): List<MovieEntity> = withContext(Dispatchers.IO) {
        movieDao.getScrapeIssueMovies(ScrapeTaskStatus.unfinishedNames)
    }

    fun observeScrapeIssueMovies(): Flow<List<MovieEntity>> =
        movieDao.observeScrapeIssueMovies(ScrapeTaskStatus.unfinishedNames)
            .distinctUntilChanged()

    suspend fun markScrapeTaskPending(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.setScrapeTaskStatusAndFailureReason(
            movieId,
            ScrapeTaskStatus.Pending.name,
            null,
            System.currentTimeMillis()
        )
    }

    suspend fun markScrapeTaskRunning(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.setScrapeTaskStatus(movieId, ScrapeTaskStatus.Running.name, System.currentTimeMillis())
    }

    suspend fun markScrapeTaskCompleted(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.setScrapeTaskStatusAndFailureReason(
            movieId,
            ScrapeTaskStatus.Completed.name,
            null,
            System.currentTimeMillis()
        )
    }

    suspend fun markScrapeTaskFailed(movieId: Long, reason: String) = withContext(Dispatchers.IO) {
        movieDao.setScrapeTaskStatusAndFailureReason(
            movieId,
            ScrapeTaskStatus.Failed.name,
            reason.trim().takeIf { it.isNotBlank() },
            System.currentTimeMillis()
        )
    }

    suspend fun resetFailedScrapeTasks(): Int = withContext(Dispatchers.IO) {
        movieDao.updateScrapeTaskStatuses(
            fromStatuses = listOf(ScrapeTaskStatus.Failed.name),
            toStatus = ScrapeTaskStatus.Pending.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun resetRunningScrapeTasks(excludedMovieIds: Set<Long> = emptySet()): Int = withContext(Dispatchers.IO) {
        if (excludedMovieIds.isEmpty()) {
            movieDao.updateScrapeTaskStatuses(
                fromStatuses = listOf(ScrapeTaskStatus.Running.name),
                toStatus = ScrapeTaskStatus.Pending.name,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            movieDao.updateScrapeTaskStatusesExcluding(
                fromStatuses = listOf(ScrapeTaskStatus.Running.name),
                excludedMovieIds = excludedMovieIds.toList(),
                toStatus = ScrapeTaskStatus.Pending.name,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun clearUnfinishedScrapeTasks(): Int = withContext(Dispatchers.IO) {
        movieDao.updateScrapeTaskStatusesAndClearFailureReason(
            fromStatuses = ScrapeTaskStatus.unfinishedNames,
            toStatus = ScrapeTaskStatus.None.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun clearFinishedScrapeTasks(): Int = withContext(Dispatchers.IO) {
        movieDao.updateScrapeTaskStatuses(
            fromStatuses = listOf(ScrapeTaskStatus.Completed.name),
            toStatus = ScrapeTaskStatus.None.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun countUnfinishedScrapeTasks(): Int = withContext(Dispatchers.IO) {
        movieDao.countScrapeTasks(ScrapeTaskStatus.unfinishedNames)
    }

    suspend fun refreshMovieAfterScrape(
        original: MovieEntity,
        scrapedStrmUri: String,
        mergeByMovieNumber: Boolean = true
    ): MovieEntity? = withContext(Dispatchers.IO) {
        val located = storedDocumentLocator.find(original.libraryRootUri, scrapedStrmUri)
        if (located == null) {
            logStore.appendMediaPath(
                operation = "刮削整理后更新数据库",
                status = "失败",
                movieId = original.id,
                fileName = original.videoName,
                databaseUri = scrapedStrmUri,
                detail = "新 STRM 无法按精确 URI 打开；影片表未写入该 URI；未自动扫描媒体库"
            )
            return@withContext null
        }
        val newFileName = located.file.name?.takeIf { it.isNotBlank() } ?: original.videoName
        val records = if (original.id > 0L) cloudStrmRecordDao.getByMovieId(original.id) else emptyList()
        val pickcodes = (records.map { it.pickcode } + listOfNotNull(original.extractPickcodeFromStrm()))
            .filter { it.isNotBlank() }
            .toSet()
        val refreshed = try {
            persistLocationBeforeMetadataRefresh(
                persistLocation = {
                    if (original.id > 0L) {
                        val now = System.currentTimeMillis()
                        val updatedMovies = movieDao.updateStoredMediaLocation(
                            id = original.id,
                            oldVideoUri = original.videoUri,
                            newVideoUri = scrapedStrmUri,
                            newVideoName = newFileName,
                            updatedAt = now
                        )
                        if (updatedMovies == 0) {
                            val current = movieDao.getMovieLite(original.id)
                            check(current?.videoUri == scrapedStrmUri) {
                                "影片路径同步冲突：movieId=${original.id}，文件=${original.videoName}；目标文件无法确认"
                            }
                        }
                        cloudStrmRecordDao.updateStoredMediaLocation(
                            movieId = original.id,
                            oldStrmUri = original.videoUri,
                            newStrmUri = scrapedStrmUri,
                            newFileName = newFileName,
                            libraryRootUri = original.libraryRootUri,
                            updatedAt = now
                        )
                    }
                },
                refreshMetadata = {
                    scanSingleMovie(
                        rootUri = Uri.parse(original.libraryRootUri),
                        videoUri = Uri.parse(scrapedStrmUri),
                        mergeByMovieNumber = mergeByMovieNumber,
                        excludedMergeMovieId = original.id.takeIf { mergeByMovieNumber }
                    )
                }
            )
        } catch (error: Throwable) {
            logStore.appendMediaPath(
                operation = "刮削整理后刷新元数据",
                status = "失败",
                movieId = original.id,
                fileName = newFileName,
                databaseUri = scrapedStrmUri,
                detail = "文件移动后已先同步影片路径；元数据刷新失败=${error.message ?: error::class.java.simpleName}；未自动扫描媒体库"
            )
            throw error
        }
        if (refreshed != null) {
            if (original.id != refreshed.id) {
                movieDao.deleteById(original.id)
            }
            pickcodes.forEach { pickcode ->
                attachCloudStrmRecordToMovie(pickcode, refreshed)
            }
            logStore.appendMediaPath(
                operation = "刮削整理后更新数据库",
                status = "成功",
                movieId = refreshed.id,
                fileName = refreshed.videoName,
                databaseUri = refreshed.videoUri,
                detail = "新路径已持久化并刷新元数据；影片表和 STRM 索引表已同步"
            )
        } else {
            logStore.appendMediaPath(
                operation = "刮削整理后更新数据库",
                status = "失败",
                movieId = original.id,
                fileName = original.videoName,
                databaseUri = scrapedStrmUri,
                detail = "文件移动后已先同步影片路径，但没有生成刷新后的影片元数据；未自动扫描媒体库"
            )
        }
        refreshed
    }

    suspend fun findMovieByNumber(rootUri: String, number: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri, normalized.movieNumberCandidateLikePattern())
            .firstOrNull { it.movieMergeKey() == normalized }
    }

    suspend fun findMovieByNumberAndVariant(rootUri: String, number: String, sourceText: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        val expectedKey = normalized + detectMovieVariant(sourceText).suffix
        movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri, expectedKey.movieNumberCandidateLikePattern())
            .firstOrNull { movie -> movie.movieNumberKey() == expectedKey }
    }

    suspend fun getPlaybackParts(movieId: Long): List<MoviePlaybackPart> = withContext(Dispatchers.IO) {
        val movie = movieDao.getMovieLite(movieId) ?: return@withContext emptyList()
        val indexedParts = getIndexedPlaybackParts(movie)
        if (indexedParts.isNotEmpty()) return@withContext indexedParts
        movie.singlePart()
    }

    private suspend fun getIndexedPlaybackParts(movie: MovieEntity): List<MoviePlaybackPart> {
        val number = extractMovieNumberInfo(movie.videoName)?.number
            ?: extractMovieNumberInfo(movie.title)?.number
            ?: movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
        val records = if (number != null) {
            cloudStrmRecordDao.getPlaybackRecords(movie.id, number, movie.libraryRootUri, movie.videoUri)
        } else {
            cloudStrmRecordDao.getByMovieId(movie.id)
        }

        return records
            .asSequence()
            .filter { it.strmUri.isNotBlank() }
            .filter { canOpenUri(it.strmUri) }
            .distinctBy { it.playbackRecordKey() }
            .map { record ->
                MoviePlaybackPart(
                    label = record.fileName.playbackPartUiLabel(),
                    videoUri = record.strmUri,
                    fileName = record.fileName
                )
            }
            .distinctBy { it.videoUri }
            .sortedWith(compareBy<MoviePlaybackPart> { it.label.playbackPartUiSortKey() }.thenBy { it.fileName.lowercase(Locale.ROOT) })
            .toList()
    }

    private suspend fun relatedCloudStrmRecords(movie: MovieEntity): List<CloudStrmRecordEntity> {
        val byMovieId = cloudStrmRecordDao.getByMovieId(movie.id)
        val number = extractMovieNumberInfo(movie.videoName)?.number
            ?: extractMovieNumberInfo(movie.title)?.number
            ?: movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
        val byNumber = number
            ?.let { cloudStrmRecordDao.getByMovieNumber(it) }
            .orEmpty()
            .filter { record ->
                (record.libraryRootUri == null || record.libraryRootUri == movie.libraryRootUri) &&
                    (record.movieId == null || record.movieId == movie.id || record.strmUri == movie.videoUri || !canOpenUri(record.strmUri))
            }
        return (byMovieId + byNumber).distinctBy { it.pickcode }
    }

    private fun canOpenUri(uriString: String): Boolean =
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } == true
        }.getOrDefault(false)

    private fun MovieEntity.resolvedScrapeFailureReason(old: MovieEntity): String? =
        if (nfoUri != null) null else old.scrapeFailureReason

    private fun MovieEntity.resolvedScrapeTaskStatus(old: MovieEntity): String =
        if (nfoUri != null) ScrapeTaskStatus.Completed.name else old.scrapeTaskStatus

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
            val saved = movieDao.getMovieByVideoUriLite(scanned.videoUri) ?: return@forEach
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
                        videoSizeBytes = null,
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
                videoSizeBytes = null,
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

    private suspend fun attachCloudStrmRecordToMovie(pickcode: String, movie: MovieEntity) {
        val existing = cloudStrmRecordDao.get(pickcode) ?: return
        cloudStrmRecordDao.upsert(
            existing.copy(
                libraryRootUri = existing.libraryRootUri ?: movie.libraryRootUri,
                movieId = movie.id,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun normalizeStrmFileName(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) error("请输入新的 STRM 文件名")
        if (trimmed.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' || it == '\r' || it == '\n' }) {
            error("文件名不能包含 / \\ : * ? \" < > |")
        }
        val fileName = if (trimmed.endsWith(".strm", ignoreCase = true)) trimmed else "$trimmed.strm"
        if (fileName.equals(".strm", ignoreCase = true)) error("请输入有效文件名")
        return fileName
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

    private fun copyStrmTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile {
        directory.findFile(fileName)?.let { existing ->
            if (existing.isFile) error("同目录已存在：$fileName")
        }
        val file = directory.createFile("application/octet-stream", fileName)
            ?: error("无法创建 STRM 文件：$fileName")
        runCatching {
            contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入 STRM 文件：$fileName")
        }.onFailure { error ->
            runCatching { file.delete() }
            throw error
        }
        return file
    }
}

data class MovieLibrarySummaries(
    val collections: List<MovieMetadataSummary> = emptyList(),
    val actors: List<MovieMetadataSummary> = emptyList(),
    val tags: List<MovieMetadataSummary> = emptyList(),
    val genres: List<MovieMetadataSummary> = emptyList(),
    val studios: List<MovieMetadataSummary> = emptyList()
)

data class MovieMetadataSummary(
    val value: String,
    val count: Int
)

sealed interface AddCustomTagResult {
    data class Added(val tag: String) : AddCustomTagResult
    data object AlreadyExists : AddCustomTagResult
    data object Blank : AddCustomTagResult
    data object MovieNotFound : AddCustomTagResult
}

sealed interface RemoveCustomTagResult {
    data class Removed(val tag: String) : RemoveCustomTagResult
    data object NotFound : RemoveCustomTagResult
    data object Blank : RemoveCustomTagResult
    data object MovieNotFound : RemoveCustomTagResult
}

data class MoviePlaybackPart(
    val label: String,
    val videoUri: String,
    val fileName: String
)

data class ScrapeTaskSummary(
    val unscraped: Int = 0,
    val pending: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0
) {
    val unfinished: Int
        get() = unscraped + pending + running + failed
}

data class DeleteMovieResult(
    val movieId: Long,
    val pickcodes: Set<String>
)

data class RenameMovieFileResult(
    val movie: MovieEntity,
    val oldFileName: String,
    val newFileName: String
)

private fun MovieEntity.singlePart(): List<MoviePlaybackPart> =
    listOf(MoviePlaybackPart(label = videoName.playbackPartUiLabel(), videoUri = videoUri, fileName = videoName))

private fun CloudStrmRecordEntity.playbackRecordKey(): String =
    pickcode.ifBlank { strmUri.ifBlank { fileName } }

private data class SimilarMovieRank(val score: Int, val distance: Int)

private data class SimilarCodeInfo(val prefix: String, val number: Int)

private const val SIMILAR_ACTOR_PREFILTER_LIMIT = 4
private const val MOVIE_LIST_PAGE_SIZE = 80

private fun MovieEntity.similarCodeInfo(): SimilarCodeInfo? {
    val source = listOf(title, originalTitle.orEmpty(), videoName).joinToString(" ")
    val match = Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})\b""").find(source) ?: return null
    return SimilarCodeInfo(
        prefix = match.groupValues[1].uppercase(Locale.ROOT),
        number = match.groupValues[2].toIntOrNull() ?: return null
    )
}

private fun String.similarNormalized(): String = trim().lowercase(Locale.ROOT)

private fun MovieEntity.movieNumberKey(): String? {
    val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
        .joinToString(" ")
    return movieVersionKeyFromText(source)
}

private fun MovieEntity.movieMergeKey(): String? {
    val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
        .joinToString(" ")
    return movieKeyFromText(source)
}

private fun String.movieNumberKeyFromText(): String? {
    return movieKeyFromText(this)
}

private fun String.escapeLikePattern(): String =
    replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

private fun String.movieNumberCandidateLikePattern(): String {
    val info = extractMovieNumberInfo(this)
    if (info != null) {
        val (prefix, digits) = info.number.split("-", limit = 2)
        return "%${prefix.escapeLikePattern()}%${digits.escapeLikePattern()}%"
    }
    return "%${escapeLikePattern()}%"
}

private fun summarizeValues(values: List<String>): List<MovieMetadataSummary> =
    values.map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, grouped) -> MovieMetadataSummary(grouped.first(), grouped.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase(Locale.ROOT) })

private fun summarizeTexts(values: List<String>): List<MovieMetadataSummary> =
    values.map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, grouped) -> MovieMetadataSummary(grouped.first(), grouped.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase(Locale.ROOT) })

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
    detectMovieVariant(this).displayName.takeIf { it.isNotBlank() }?.let { return it }
    return segmentPartLabel() ?: "姝ｇ墖"
}

private fun String.playbackPartSortKey(): Int =
    when {
        this == "姝ｇ墖" -> 0
        this == "4K" -> 50
        length == 1 && first() in 'A'..'Z' -> 10 + first().code - 'A'.code
        matches(Regex("""P\d{1,2}""")) -> 100 + (drop(1).toIntOrNull() ?: 99)
        else -> Int.MAX_VALUE
    }

private fun String.playbackPartDisplayLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .displayName
        .takeIf { it.isNotBlank() }
    return when {
        part != null && variant != null -> "$part $variant"
        part != null -> part
        variant != null -> variant
        else -> "默认"
    }
}

private fun String.playbackPartDisplaySortKey(): Int {
    val partToken = substringBefore(' ')
    val partScore = when {
        this == "默认" -> 0
        partToken == "4K" || partToken == "8K" || partToken == "60FPS" || partToken == "4K60FPS" || partToken == "8K60FPS" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 3
        contains("4K") -> 2
        contains("60FPS") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}
private fun String.playbackPartUiLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .displayName
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
        partToken == "4K" || partToken == "8K" || partToken == "60FPS" || partToken == "4K60FPS" || partToken == "8K60FPS" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 3
        contains("4K") -> 2
        contains("60FPS") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}

internal suspend fun <T> persistLocationBeforeMetadataRefresh(
    persistLocation: suspend () -> Unit,
    refreshMetadata: suspend () -> T
): T {
    persistLocation()
    return refreshMetadata()
}
