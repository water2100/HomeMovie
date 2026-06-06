package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.scraper.ActorAvatarStore
import com.example.localmovielibrary.scraper.Dmm2Scraper
import com.example.localmovielibrary.scraper.DmmScraper
import com.example.localmovielibrary.scraper.MissavScraper
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.MovieScraperRegistry
import com.example.localmovielibrary.scraper.NetworkProbe
import com.example.localmovielibrary.scraper.NfoWriter
import com.example.localmovielibrary.scraper.OfficialScraper
import com.example.localmovielibrary.scraper.ScrapeLogStore
import com.example.localmovielibrary.scraper.ScrapeRunResult
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.ScrapedMovieInfo
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.displayNumberWithVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.playbackSourceSuffix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.system.measureTimeMillis

class StrmScrapeRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logStore: ScrapeLogStore = ScrapeLogStore(context),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val networkProbe: NetworkProbe = NetworkProbe(ioDispatcher = ioDispatcher),
    private val dmmScraper: DmmScraper = DmmScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val dmm2Scraper: Dmm2Scraper = Dmm2Scraper(client = httpClient, ioDispatcher = ioDispatcher, logger = logStore::append),
    private val officialScraper: OfficialScraper = OfficialScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val missavScraper: MissavScraper = MissavScraper(
        cookieProvider = settingsRepository::getMissavCookies,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    private val scraperRegistry: MovieScraperRegistry = MovieScraperRegistry(
        listOf(dmmScraper, dmm2Scraper, officialScraper, missavScraper)
    ),
    private val imageDownloadService: ImageDownloadService = ImageDownloadService(
        httpClient = httpClient,
        retryCountProvider = settingsRepository::getImageDownloadRetryCount,
        logger = logStore::append,
        ioDispatcher = ioDispatcher
    )
) {
    private val actorAvatarStore = ActorAvatarStore(context)
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var actorAvatarJob: Job? = null
    private val scrapeAdmissionMutex = Mutex()
    private val missavScrapeMutex = Mutex()
    private val _scrapeQueueState = MutableStateFlow(ScrapeQueueState())
    private val _actorAvatarUpdateState = MutableStateFlow(ActorAvatarUpdateState())
    val scrapeQueueState: StateFlow<ScrapeQueueState> = _scrapeQueueState
    val actorAvatarUpdateState: StateFlow<ActorAvatarUpdateState> = _actorAvatarUpdateState

    fun logDates(): List<String> = logStore.dates()

    fun readLogs(date: String = logDates().firstOrNull().orEmpty()): String = logStore.read(date)

    fun logUpdates(): StateFlow<Long> = logStore.updates

    fun clearLogs(date: String? = null) {
        if (date == null) logStore.clearAll() else logStore.clear(date)
    }

    fun appendLog(message: String) {
        logStore.append(message)
    }

    private suspend fun <T> runQueuedScrapeTask(
        label: String,
        serialMutex: Mutex? = null,
        block: suspend () -> T
    ): T = withContext(ioDispatcher) {
        _scrapeQueueState.update { state ->
            state.copy(waitingCount = state.waitingCount + 1)
        }
        if (serialMutex != null) {
            return@withContext serialMutex.withLock {
                runAdmittedScrapeTask(label, block)
            }
        }
        runAdmittedScrapeTask(label, block)
    }

    private suspend fun <T> runAdmittedScrapeTask(label: String, block: suspend () -> T): T {
        var admitted = false
        var waitingLogged = false
        try {
            while (!admitted) {
                val limit = settingsRepository.getScrapeConcurrencyLimit()
                scrapeAdmissionMutex.withLock {
                    val state = _scrapeQueueState.value
                    if (state.runningCount < limit) {
                        val nextRunningCount = state.runningCount + 1
                        _scrapeQueueState.value = state.copy(
                            isRunning = true,
                            runningLabel = label,
                            runningCount = nextRunningCount,
                            waitingCount = (state.waitingCount - 1).coerceAtLeast(0),
                            startedAtMillis = state.startedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        admitted = true
                    }
                }
                if (!admitted) {
                    if (!waitingLogged) {
                        logStore.append("刮削任务等待队列：$label，当前并发=${_scrapeQueueState.value.runningCount}/$limit")
                        waitingLogged = true
                    }
                    delay(SCRAPE_QUEUE_POLL_INTERVAL_MS)
                }
            }
            try {
                return block()
            } finally {
                scrapeAdmissionMutex.withLock {
                    _scrapeQueueState.update { state ->
                        val nextRunningCount = (state.runningCount - 1).coerceAtLeast(0)
                        state.copy(
                            isRunning = nextRunningCount > 0,
                            runningLabel = if (nextRunningCount > 0) state.runningLabel else null,
                            runningCount = nextRunningCount,
                            startedAtMillis = if (nextRunningCount > 0) state.startedAtMillis else 0L
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (!admitted) {
                _scrapeQueueState.update { state ->
                    state.copy(waitingCount = (state.waitingCount - 1).coerceAtLeast(0))
                }
            }
            throw error
        }
    }

    private fun appendMovieDivider(title: String, number: String, fileName: String, source: ScrapeSource? = null) {
        val sourceText = source?.let { ", source=${it.label}" }.orEmpty()
        logStore.append("----------------------------------------")
        logStore.append("$title: number=$number, file=$fileName$sourceText")
    }

    suspend fun findStrmUriByNumber(
        libraryRootUri: String,
        number: String,
        partLabel: String?,
        nameHint: String? = null
    ): String? = withContext(ioDispatcher) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(libraryRootUri)) ?: return@withContext null
        val hintToken = nameHint?.distinctPickcodeSuffix()?.removePrefix("_")
        val expectedVariant = nameHint?.let { detectMovieVariant(it) }
        fun walk(directory: DocumentFile): String? {
            directory.listFiles().forEach { child ->
                if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                    walk(child)?.let { return it }
                    return@forEach
                }
                if (!child.isFile || !child.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val name = child.name.orEmpty()
                if (!name.contains(number, ignoreCase = true)) return@forEach
                if (hintToken != null && !name.contains(hintToken, ignoreCase = true)) return@forEach
                if (expectedVariant != null && detectMovieVariant(name) != expectedVariant) return@forEach
                if (partLabel != null && !Regex("""(?i)[-_ ]${Regex.escape(partLabel)}(?:\.strm$|[^a-z0-9])""").containsMatchIn(name)) {
                    return@forEach
                }
                return child.uri.toString()
            }
            return null
        }
        return@withContext walk(root)
    }

    fun getDefaultScrapeSource(): ScrapeSource = settingsRepository.getDefaultScrapeSource()

    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        var reachable = false
        logStore.append("Start Google connectivity check, timeout 5s")
        val elapsedMs = measureTimeMillis {
            reachable = networkProbe.canReachGoogle()
        }
        logStore.append("Google connectivity ${if (reachable) "passed" else "failed"}, elapsed=${elapsedMs}ms")
        reachable
    }

    suspend fun scrapeMovie(movie: MovieEntity, source: ScrapeSource, forceDistinct: Boolean = false): ScrapedMovieInfo =
        scrapeMovieWithOutput(movie, source, forceDistinct).info

    suspend fun scrapeMovieWithOutput(movie: MovieEntity, source: ScrapeSource, forceDistinct: Boolean = false): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        scrapeTargetWithOutput(target, source, forceDistinct)
    }

    suspend fun scrapeStrmUriWithOutput(
        libraryRootUri: String,
        strmUri: String,
        source: ScrapeSource,
        forceDistinct: Boolean = false
    ): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape-uri:${Uri.parse(strmUri).lastPathSegment.orEmpty()}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(libraryRootUri))
            ?: error("影片库目录不可用")
        val target = findTargetFast(root, libraryRootUri, strmUri)
            ?: error("当前 STRM 文件不存在")
        scrapeTargetWithOutput(target, source, forceDistinct)
    }

    private suspend fun scrapeTargetWithOutput(
        target: StrmTarget,
        source: ScrapeSource,
        forceDistinct: Boolean
    ): ScrapedMovieWriteResult {
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")

        logStore.append("Start scrape: file=${target.file.name}, number=$number, source=${source.label}")
        appendMovieDivider("Start movie scrape", number, target.file.name.orEmpty(), source)
        val info = scraperRegistry.scrape(source, number)
        logStore.append("Metadata fetched: ${info.title.ifBlank { number }}")
        val strmUri = writeOrganizedScrapeFiles(target, info, number, forceDistinct)
        downloadActorAvatars(info)
        logStore.append("Movie scrape finished: $number")
        return ScrapedMovieWriteResult(info = info, strmUri = strmUri)
    }

    suspend fun scrapeMovieWithMissavHtml(movie: MovieEntity, html: String, cookie: String): ScrapedMovieInfo =
        scrapeMovieWithMissavHtmlOutput(movie, html, cookie).info

    suspend fun scrapeMovieWithMissavHtmlOutput(movie: MovieEntity, html: String, cookie: String): ScrapedMovieWriteResult =
        runQueuedScrapeTask(
            label = "missav-webview-scrape:${movie.videoName}",
            serialMutex = missavScrapeMutex
        ) {
            if (cookie.isNotBlank()) {
                settingsRepository.saveMissavCookies(cookie)
                logStore.append("MissAV WebView cookie saved")
            }
            val target = findTargetForMovie(movie)
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
                ?: error("无法从文件名提取番号：${target.file.name}")

            logStore.append("Parse MissAV WebView HTML: $number")
            appendMovieDivider("Start MissAV WebView scrape", number, target.file.name.orEmpty(), ScrapeSource.Missav)
            val info = missavScraper.scrapeFromHtml(number, html)
            logStore.append("MissAV WebView metadata parsed: ${info.title.ifBlank { number }}")
            val strmUri = writeOrganizedScrapeFiles(target, info, number)
            downloadActorAvatars(info)
            logStore.append("MissAV WebView scrape finished: $number")
            ScrapedMovieWriteResult(info = info, strmUri = strmUri)
        }

    suspend fun rescrapeMovie(movie: MovieEntity, source: ScrapeSource): ScrapedMovieInfo = runQueuedScrapeTask(
        label = "rescrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: MovieNumberExtractor.extract(movie.title)
            ?: error("无法从文件名提取番号：${target.file.name}")

        logStore.append("Start rescrape: file=${target.file.name}, number=$number, source=${source.label}")
        appendMovieDivider("Start movie rescrape", number, target.file.name.orEmpty(), source)
        val info = scraperRegistry.scrape(source, number)
        logStore.append("Rescrape metadata fetched: ${info.title.ifBlank { number }}")
        rewriteScrapeFilesInPlace(target, info)
        downloadActorAvatars(info)
        logStore.append("Movie rescrape finished: $number")
        info
    }

    suspend fun rescrapeMovieWithMissavHtml(movie: MovieEntity, html: String, cookie: String): ScrapedMovieInfo =
        runQueuedScrapeTask(
            label = "missav-webview-rescrape:${movie.videoName}",
            serialMutex = missavScrapeMutex
        ) {
            if (cookie.isNotBlank()) {
                settingsRepository.saveMissavCookies(cookie)
                logStore.append("MissAV WebView cookie saved")
            }
            val target = findTargetForMovie(movie)
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
                ?: MovieNumberExtractor.extract(movie.title)
                ?: error("无法从文件名提取番号：${target.file.name}")

            logStore.append("Parse MissAV WebView HTML for rescrape: $number")
            appendMovieDivider("Start MissAV WebView rescrape", number, target.file.name.orEmpty(), ScrapeSource.Missav)
            val info = missavScraper.scrapeFromHtml(number, html)
            logStore.append("MissAV WebView rescrape parsed: ${info.title.ifBlank { number }}")
            rewriteScrapeFilesInPlace(target, info)
            downloadActorAvatars(info)
            logStore.append("MissAV WebView rescrape finished: $number")
            info
        }

    suspend fun scrapeUnscrapedStrm(source: ScrapeSource): ScrapeRunResult = runQueuedScrapeTask(
        label = "batch:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        logStore.append("Start batch scrape: ${source.label}")
        if (!canReachGoogle()) {
            error("Google 连通性测试失败")
        }

        val rootUri = settingsRepository.getLibraryRootUri()
            ?: error("请先在设置中选择影片库目录")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            ?: error("影片库目录不可用")
        if (!root.canWrite()) {
            logStore.append("Warning: library root may not be writable; NFO/images may fail")
        }

        val targets = mutableListOf<StrmTarget>()
        collectTargets(root, targets)
        logStore.append("Found unscraped STRM files: ${targets.size}")

        var success = 0
        var skipped = 0
        var failed = 0
        targets.forEach { target ->
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            if (number == null) {
                skipped += 1
                logStore.append("Skipped: cannot extract number from ${target.file.name}")
                return@forEach
            }

            runCatching {
                logStore.append("Scraping $number, file=${target.file.name}")
                appendMovieDivider("Start batch movie scrape", number, target.file.name.orEmpty(), source)
                val info = scraperRegistry.scrape(source, number)
                logStore.append("Metadata fetched: $number")
                writeOrganizedScrapeFiles(target, info, number)
                downloadActorAvatars(info)
                success += 1
                logStore.append("Success: $number -> ${info.title}")
            }.onFailure { error ->
                failed += 1
                logStore.append("Failed: $number, ${error.message ?: error::class.java.simpleName}")
            }
        }
        val result = ScrapeRunResult(targets.size, success, skipped, failed)
        logStore.append("Batch scrape finished: success=$success, failed=$failed, skipped=$skipped")
        result
    }

    suspend fun clearScrapeFiles(movie: MovieEntity): String = withContext(ioDispatcher) {
        val target = findTargetForMovie(movie)
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: MovieNumberExtractor.extract(movie.title)
            ?: error("无法安全提取影片番号")

        logStore.append("Start clearing scrape files: $number")
        val restoredFileName = "$number.strm"
        val directoryName = target.directory.name.orEmpty()
        val isOrganizedFolder = directoryName.contains(number, ignoreCase = true) &&
            (directoryName.startsWith("\u3010") || directoryName.startsWith("[")) &&
            target.parentDirectory != null

        if (isOrganizedFolder) {
            val parent = target.parentDirectory ?: error("无法定位父目录")
            copyStrmFile(target.file, parent, restoredFileName)
            logStore.append("STRM restored to parent: $restoredFileName")
            deleteRecursively(target.directory)
            logStore.append("Deleted generated movie directory: ${target.directory.name}")
        } else {
            deleteMetadataFiles(target.directory, target.baseName)
            logStore.append("Deleted metadata files for baseName=${target.baseName}")
        }

        logStore.append("Clear scrape files finished: $number")
        number
    }

    fun startUpdateMissingActorAvatars(movies: List<MovieEntity>) {
        if (actorAvatarJob?.isActive == true) {
            logStore.append("Actor avatar update is already running")
            return
        }
        actorAvatarJob = backgroundScope.launch {
            _actorAvatarUpdateState.value = ActorAvatarUpdateState(isUpdating = true, message = "Updating missing actor avatars...")
            runCatching { updateMissingActorAvatarsInternal(movies) }
                .onSuccess { result ->
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = if (result.totalMissing == 0) {
                            "Actor avatars are already up to date"
                        } else {
                            "Actor avatars updated: ${result.downloaded}/${result.totalMissing}"
                        },
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion + 1
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logStore.append("Actor avatar background update failed: ${error.message ?: error::class.java.simpleName}")
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = error.message ?: "演员头像更新失败",
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion
                    )
                }
        }
    }

    private suspend fun updateMissingActorAvatarsInternal(movies: List<MovieEntity>): ActorAvatarUpdateResult {
        val missingActors = movies
            .flatMap { it.actors }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedActorName() }
            .filterNot { actorAvatarStore.hasAvatar(it) }

        if (missingActors.isEmpty()) {
            logStore.append("Actor avatar update: no missing avatars")
            return ActorAvatarUpdateResult(totalMissing = 0, downloaded = 0, scrapedMovies = 0)
        }

        logStore.append("Start actor avatar update, missing=${missingActors.size}")
        val pending = missingActors.toMutableSet()
        val visitedNumbers = mutableSetOf<String>()
        var scrapedMovies = 0

        movies
            .filter { movie -> movie.actors.any { actor -> pending.any { it.sameActor(actor) } } }
            .forEach { movie ->
                if (pending.isEmpty()) return@forEach
                val number = MovieNumberExtractor.extract(movie.videoName)
                    ?: MovieNumberExtractor.extract(movie.title)
                    ?: MovieNumberExtractor.extract(movie.originalTitle.orEmpty())
                    ?: return@forEach
                if (!visitedNumbers.add(number.uppercase())) return@forEach

                runCatching {
                    logStore.append("Query DMM2 for actor avatars: $number")
                    val info = dmm2Scraper.scrape(number)
                    scrapedMovies += 1
                    downloadActorAvatars(info)
                    val resolved = pending.filter { actorAvatarStore.hasAvatar(it) }
                    pending.removeAll(resolved.toSet())
                    if (resolved.isNotEmpty()) {
                        logStore.append("Actor avatars resolved: ${resolved.joinToString(", ")}")
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logStore.append("DMM2 actor avatar query failed: $number, ${error.message ?: error::class.java.simpleName}")
                }
            }

        val downloaded = missingActors.count { actorAvatarStore.hasAvatar(it) }
        logStore.append("Actor avatar update finished: downloaded=$downloaded/${missingActors.size}, scrapedMovies=$scrapedMovies")
        return ActorAvatarUpdateResult(
            totalMissing = missingActors.size,
            downloaded = downloaded,
            scrapedMovies = scrapedMovies
        )
    }

    private fun findTargetForMovie(movie: MovieEntity): StrmTarget {
        if (!movie.videoName.endsWith(".strm", ignoreCase = true)) {
            error("当前影片不是 STRM 文件")
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
            ?: error("影片库目录不可用")
        return findTargetFast(root, movie.libraryRootUri, movie.videoUri)
            ?: findTarget(root, movie.videoUri)
            ?: findMovedTargetForMovie(root, movie)?.also { target ->
                logStore.append("当前记录 STRM 已移动，已定位到整理后的文件：${target.file.name}")
            }
            ?: error("当前 STRM 文件不存在")
    }

    private fun findMovedTargetForMovie(root: DocumentFile, movie: MovieEntity): StrmTarget? {
        val sourceName = movie.videoName.orEmpty()
        val number = MovieNumberExtractor.extract(sourceName)
            ?: MovieNumberExtractor.extract(movie.title)
            ?: MovieNumberExtractor.extract(movie.originalTitle.orEmpty())
            ?: return null
        val hintToken = sourceName.distinctPickcodeSuffix()?.removePrefix("_")?.takeIf { it.isNotBlank() }
        val expectedVariant = detectMovieVariant(sourceName)
        val partLabel = extractMovieNumberInfo(sourceName)?.partLabel

        fun walk(directory: DocumentFile, parentDirectory: DocumentFile?): StrmTarget? {
            directory.listFiles().forEach { child ->
                if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                    walk(child, directory)?.let { return it }
                    return@forEach
                }
                if (!child.isFile || !child.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val name = child.name.orEmpty()
                if (!name.contains(number, ignoreCase = true)) return@forEach
                if (hintToken != null && !name.contains(hintToken, ignoreCase = true)) return@forEach
                if (detectMovieVariant(name) != expectedVariant) return@forEach
                if (partLabel != null && !Regex("""(?i)[-_ ]${Regex.escape(partLabel)}(?:\.strm$|[^a-z0-9])""").containsMatchIn(name)) {
                    return@forEach
                }
                val baseName = name.substringBeforeLast('.', name)
                return StrmTarget(directory, child, baseName, parentDirectory)
            }
            return null
        }

        return walk(root, null)
    }

    private suspend fun writeOrganizedScrapeFiles(target: StrmTarget, info: ScrapedMovieInfo, fallbackNumber: String, forceDistinct: Boolean = false): String {
        val sourceName = target.file.name.orEmpty()
        val baseNumber = info.number.ifBlank { fallbackNumber }.uppercase()
        val variant = detectMovieVariant(sourceName)
        val writeInfo = info.copy(number = baseNumber)
        val distinctSuffix = if (forceDistinct) target.file.name.orEmpty().distinctPickcodeSuffix() else null
        val baseName = buildMovieBaseName(writeInfo, baseNumber) + distinctSuffix.orEmpty()
        val movieDirectory = if (target.directory.name == baseName) {
            target.directory
        } else {
            val actorDirectory = createOrReuseActorDirectory(target.directory, writeInfo)
            createOrReuseMovieDirectory(actorDirectory, baseName)
        }

        logStore.append("Movie directory: ${movieDirectory.name}")

        val partLabel = extractMovieNumberInfo(target.file.name.orEmpty())?.partLabel
        val strmName = "$baseName${playbackSourceSuffix(partLabel, variant)}.strm"
        val newStrm = copyStrmFile(target.file, movieDirectory, strmName)
        logStore.append("STRM written: $strmName")
        if (target.file.uri != newStrm.uri) {
            if (target.file.delete()) {
                logStore.append("Old STRM deleted: ${target.file.name}")
            } else {
                logStore.append("Warning: failed to delete old STRM: ${target.file.name}")
            }
        }

        val nfoName = "$baseName.nfo"
        logStore.append("Write NFO: $nfoName")
        writeTextFile(movieDirectory, nfoName, NfoWriter.build(writeInfo))
        logStore.append("NFO written: $nfoName")

        val poster = info.posterUrl.ifBlank { info.thumbUrl }
        if (poster.isNotBlank()) {
            val posterName = "$baseName-poster.jpg"
            logStore.append("Download poster: $poster")
            downloadImageToFile(movieDirectory, posterName, poster)
            logStore.append("Poster written: $posterName")
        } else {
            logStore.append("Poster URL is blank; skipped")
        }

        if (info.thumbUrl.isNotBlank()) {
            val thumbName = "$baseName-thumb.jpg"
            logStore.append("Download thumb: ${info.thumbUrl}")
            downloadImageToFile(movieDirectory, thumbName, info.thumbUrl)
            logStore.append("Thumb written: $thumbName")

            val fanartName = "$baseName-fanart.jpg"
            logStore.append("Copy thumb as fanart: $fanartName")
            downloadImageToFile(movieDirectory, fanartName, info.thumbUrl)
            logStore.append("Fanart written: $fanartName")
        } else {
            logStore.append("Thumb URL is blank; skipped")
        }
        deleteLegacyNfoXml(target)
        return newStrm.uri.toString()
    }

    private suspend fun rewriteScrapeFilesInPlace(target: StrmTarget, info: ScrapedMovieInfo) {
        val baseName = target.baseName
        val directory = target.directory
        val nfoName = "$baseName.nfo"
        val displayNumber = displayNumberWithVariant(info.number, target.file.name.orEmpty())
        val writeInfo = info.copy(number = displayNumber)
        logStore.append("Rewrite NFO: $nfoName")
        writeTextFile(directory, nfoName, NfoWriter.build(writeInfo))
        logStore.append("NFO rewritten: $nfoName")

        val posterName = "$baseName-poster.jpg"
        val thumbName = "$baseName-thumb.jpg"
        val fanartName = "$baseName-fanart.jpg"
        val hasPoster = directory.hasAnyFile(posterName, "poster.jpg", "movie-poster.jpg")
        val hasThumb = directory.hasAnyFile(thumbName, "thumb.jpg")
        val hasFanart = directory.hasAnyFile(fanartName, "fanart.jpg", "movie-fanart.jpg")
        if (hasPoster && hasThumb && hasFanart) {
            logStore.append("poster/thumb/fanart already exist; skipped image downloads")
            deleteLegacyNfoXml(target)
            return
        }

        val poster = info.posterUrl.ifBlank { info.thumbUrl }
        if (!hasPoster && poster.isNotBlank()) {
            logStore.append("Poster missing; downloading: $poster")
            downloadImageToFile(directory, posterName, poster)
            logStore.append("Poster written: $posterName")
        }
        if (!hasThumb && info.thumbUrl.isNotBlank()) {
            logStore.append("Thumb missing; downloading: ${info.thumbUrl}")
            downloadImageToFile(directory, thumbName, info.thumbUrl)
            logStore.append("Thumb written: $thumbName")
        }
        if (!hasFanart && info.thumbUrl.isNotBlank()) {
            logStore.append("Fanart missing; using thumb: $fanartName")
            downloadImageToFile(directory, fanartName, info.thumbUrl)
            logStore.append("Fanart written: $fanartName")
        }
        deleteLegacyNfoXml(target)
    }

    private suspend fun downloadActorAvatars(info: ScrapedMovieInfo) {
        if (info.actorImageUrls.isEmpty()) return
        info.actorImageUrls.forEach { (actorName, imageUrl) ->
            if (actorName.isBlank() || imageUrl.isBlank()) return@forEach
            if (actorAvatarStore.hasAvatar(actorName)) {
                logStore.append("Actor avatar already exists; skipped: $actorName")
                return@forEach
            }
            runCatching {
                logStore.append("Download actor avatar: $actorName -> $imageUrl")
                actorAvatarStore.saveAvatar(actorName, imageDownloadService.downloadImageBytes(imageUrl))
                logStore.append("Actor avatar downloaded: $actorName")
            }.onFailure { error ->
                logStore.append("Actor avatar download failed: $actorName, ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private fun buildMovieBaseName(info: ScrapedMovieInfo, fallbackNumber: String): String {
        val actor = info.actors.firstOrNull { it.isNotBlank() }
            ?.sanitizeFileName()
            ?.ifBlank { null }
            ?: "\u672A\u77E5\u6F14\u5458"
        val number = info.number.ifBlank { fallbackNumber }
            .uppercase()
            .sanitizeFileName()
        return "\u3010$actor\u3011$number"
    }

    private fun createOrReuseActorDirectory(parent: DocumentFile, info: ScrapedMovieInfo): DocumentFile {
        val desiredName = actorGroupFolderName(info)
        if (parent.name == desiredName) return parent
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        logStore.append("Create actor directory: $desiredName")
        return parent.createDirectory(desiredName)
            ?: error("无法创建演员目录：$desiredName")
    }

    private fun actorGroupFolderName(info: ScrapedMovieInfo): String {
        val actors = info.actors
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return when (actors.size) {
            0 -> "\u672A\u77E5\u6F14\u5458"
            1 -> actors.first().sanitizeFileName().ifBlank { "\u672A\u77E5\u6F14\u5458" }
            else -> "\u591A\u4EBA\u4F5C\u54C1"
        }
    }

    private fun createOrReuseMovieDirectory(parent: DocumentFile, desiredName: String): DocumentFile {
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        var candidate = desiredName
        var index = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$desiredName-$index"
            index += 1
        }
        logStore.append("Create movie directory: $candidate")
        return parent.createDirectory(candidate) ?: error("无法创建影片目录：$candidate")
    }

    private fun copyStrmFile(source: DocumentFile, directory: DocumentFile, fileName: String): DocumentFile {
        val content = context.contentResolver.openInputStream(source.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取源 STRM 文件：${source.name}")
        return writeTextFile(directory, fileName, content)
    }

    private fun deleteLegacyNfoXml(target: StrmTarget) {
        val legacyName = "${target.baseName}.nfo.xml"
        target.directory.findFile(legacyName)?.let { legacy ->
            if (legacy.delete()) {
                logStore.append("Deleted legacy NFO file: $legacyName")
            }
        }
    }

    private fun collectTargets(directory: DocumentFile, out: MutableList<StrmTarget>) {
        val children = directory.listFiles().toList()
        val names = children.mapNotNull { it.name?.lowercase() }.toSet()
        children.filter { it.isFile && it.name.orEmpty().endsWith(".strm", ignoreCase = true) }.forEach { strm ->
            val baseName = strm.name.orEmpty().substringBeforeLast('.', strm.name.orEmpty())
            if ("${baseName.lowercase()}.nfo" !in names) {
                out += StrmTarget(directory, strm, baseName, parentDirectory = null)
            }
        }
        children.filter { it.isDirectory && !it.isExcludedAssetDirectory() }.forEach { collectTargets(it, out) }
    }

    private fun findTarget(directory: DocumentFile, videoUri: String, parentDirectory: DocumentFile? = null): StrmTarget? {
        directory.listFiles().forEach { child ->
            if (child.isFile && child.uri.toString() == videoUri) {
                val baseName = child.name.orEmpty().substringBeforeLast('.', child.name.orEmpty())
                return StrmTarget(directory, child, baseName, parentDirectory)
            }
            if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                findTarget(child, videoUri, directory)?.let { return it }
            }
        }
        return null
    }

    private fun findTargetFast(root: DocumentFile, rootUriString: String, videoUriString: String): StrmTarget? {
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

        val parentSegments = segments.dropLast(1)
        val fileName = segments.last()
        var parentDirectory: DocumentFile? = null
        val directory = parentSegments.fold(root as DocumentFile?) { current, segment ->
            parentDirectory = current
            current?.findFile(segment)?.takeIf { it.isDirectory }
        } ?: root.takeIf { parentSegments.isEmpty() } ?: return null
        val file = directory.findFile(fileName)?.takeIf { it.isFile } ?: return null
        val baseName = file.name.orEmpty().substringBeforeLast('.', file.name.orEmpty())
        return StrmTarget(
            directory = directory,
            file = file,
            baseName = baseName,
            parentDirectory = parentDirectory?.takeIf { directory.uri != root.uri }
        )
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

    private fun writeTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile {
        directory.findFile(fileName)?.delete()
        val file = directory.createFile(GENERIC_FILE_MIME_TYPE, fileName)
            ?: error("无法创建文件：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入文件：$fileName")
        return file
    }

    private suspend fun downloadImageToFile(directory: DocumentFile, fileName: String, url: String) {
        directory.findFile(fileName)?.delete()
        val bytes = imageDownloadService.downloadImageBytes(url)
        val file = directory.createFile("image/jpeg", fileName)
            ?: error("无法创建图片：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: error("无法写入图片：$fileName")
    }

    private fun deleteMetadataFiles(directory: DocumentFile, baseName: String) {
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
        val names = buildList {
            add("$baseName.nfo")
            add("$baseName.nfo.xml")
            imageExtensions.forEach { ext ->
                add("$baseName-poster.$ext")
                add("$baseName-thumb.$ext")
                add("$baseName-fanart.$ext")
                add("poster.$ext")
                add("thumb.$ext")
                add("fanart.$ext")
                add("movie-poster.$ext")
                add("movie-fanart.$ext")
            }
        }
        names.forEach { name ->
            directory.findFile(name)?.delete()
        }
    }

    private fun DocumentFile.hasAnyFile(vararg names: String): Boolean {
        return names.any { name -> findFile(name) != null }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun String.distinctPickcodeSuffix(): String? {
        val token = substringBeforeLast('.', this)
            .substringAfterLast('_', "")
            .takeIf { it.length >= 8 && it.all { char -> char.isLetterOrDigit() } }
            ?: return null
        return "_${token.take(8)}"
    }

    private fun String.normalizedActorName(): String = trim().lowercase()

    private fun String.sameActor(other: String): Boolean =
        normalizedActorName() == other.normalizedActorName()

    private fun DocumentFile.isExcludedAssetDirectory(): Boolean {
        val normalized = name.orEmpty().trim().lowercase().replace('_', ' ').replace('-', ' ')
        return normalized == "extrafanart" || normalized == "behind the scenes"
    }

    private data class StrmTarget(
        val directory: DocumentFile,
        val file: DocumentFile,
        val baseName: String,
        val parentDirectory: DocumentFile?
    )

    private val ScrapeSource.label: String
        get() = when (this) {
            ScrapeSource.Dmm -> "DMM"
            ScrapeSource.Dmm2 -> "DMM2"
            ScrapeSource.Official -> "Official"
            ScrapeSource.Missav -> "MissAV"
        }

    private fun ScrapeSource.serialScrapeMutex(): Mutex? =
        if (this == ScrapeSource.Missav) missavScrapeMutex else null

    private companion object {
        const val GENERIC_FILE_MIME_TYPE = "application/octet-stream"
        const val SCRAPE_QUEUE_POLL_INTERVAL_MS = 250L
    }
}

data class ActorAvatarUpdateResult(
    val totalMissing: Int,
    val downloaded: Int,
    val scrapedMovies: Int
)

data class ScrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val strmUri: String
)

data class ActorAvatarUpdateState(
    val isUpdating: Boolean = false,
    val message: String? = null,
    val refreshVersion: Int = 0
)

data class ScrapeQueueState(
    val isRunning: Boolean = false,
    val runningLabel: String? = null,
    val runningCount: Int = 0,
    val waitingCount: Int = 0,
    val startedAtMillis: Long = 0L
)
