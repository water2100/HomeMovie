package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.scraper.ActorAvatarStore
import com.example.localmovielibrary.scraper.CustomJsonScraper
import com.example.localmovielibrary.scraper.Dmm2Scraper
import com.example.localmovielibrary.scraper.DmmScraper
import com.example.localmovielibrary.scraper.JavdbScraper
import com.example.localmovielibrary.scraper.JavbusScraper
import com.example.localmovielibrary.scraper.MgstageScraper
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.MovieScraperRegistry
import com.example.localmovielibrary.scraper.NetworkProbe
import com.example.localmovielibrary.scraper.NfoWriter
import com.example.localmovielibrary.scraper.OfficialScraper
import com.example.localmovielibrary.scraper.ScrapeLogStore
import com.example.localmovielibrary.scraper.ScrapeLogContext
import com.example.localmovielibrary.scraper.ScrapeTaskJournal
import com.example.localmovielibrary.scraper.ScrapeTaskReport
import com.example.localmovielibrary.scraper.ScrapeEventLevel
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.ScrapeProxySelector
import com.example.localmovielibrary.scraper.ScrapedMovieInfo
import com.example.localmovielibrary.scraper.normalizeMgstageSearchNumber
import com.example.localmovielibrary.scraper.rewriteNumberPrefix
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.displayNumberWithVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.movieKeyFromText
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

data class ClearScrapeResult(
    val number: String,
    val strmUri: String
)

class StrmScrapeRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logStore: ScrapeLogStore = ScrapeLogStore(context),
    private val taskJournal: ScrapeTaskJournal = ScrapeTaskJournal(context),
    private val storedDocumentLocator: StoredDocumentLocator = StoredDocumentLocator(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .proxySelector(ScrapeProxySelector(settingsRepository))
        .build(),
    private val networkProbe: NetworkProbe = NetworkProbe(client = httpClient, ioDispatcher = ioDispatcher),
    private val remoteScrapeConfigRepository: RemoteScrapeConfigRepository = RemoteScrapeConfigRepository(
        settingsRepository = settingsRepository,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    private val dmmScraper: DmmScraper = DmmScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val dmm2Scraper: Dmm2Scraper = Dmm2Scraper(
        client = httpClient,
        ioDispatcher = ioDispatcher,
        logger = logStore::append,
        // The repository retries the complete source request using the user setting.
        // Keep the scraper's transport retry at one attempt to avoid multiplying retries.
        graphqlRetryCountProvider = { 1 }
    ),
    private val officialScraper: OfficialScraper = OfficialScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val mgstageScraper: MgstageScraper = MgstageScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javbusScraper: JavbusScraper = JavbusScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javdbScraper: JavdbScraper = JavdbScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val customJsonScraper: CustomJsonScraper = CustomJsonScraper(settingsRepository = settingsRepository, client = httpClient, ioDispatcher = ioDispatcher),
    private val scraperRegistry: MovieScraperRegistry = MovieScraperRegistry(
        listOf(dmmScraper, dmm2Scraper, officialScraper, mgstageScraper, javbusScraper, javdbScraper, customJsonScraper)
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

    fun removeLegacyLogs(): Int = logStore.removeLegacyLines()

    fun appendLog(message: String) {
        logStore.append(message)
    }

    fun latestScrapeReport(movieId: Long): ScrapeTaskReport? = taskJournal.latestForMovie(movieId)
    val scrapeTaskReports: StateFlow<List<ScrapeTaskReport>> get() = taskJournal.reports

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
        val sourceText = source?.let { "，来源=${it.label}" }.orEmpty()
        logStore.append("【番号分隔】$number")
        logStore.append("[影片刮削] 状态=开始，类型=$title，番号=$number，文件=$fileName$sourceText")
    }

    fun getDefaultScrapeSource(): ScrapeSource = settingsRepository.getDefaultScrapeSource()

    suspend fun testCustomJsonScrape(number: String): ScrapedMovieInfo =
        customJsonScraper.scrape(number)

    suspend fun previewCustomJsonPathCandidates(number: String) =
        customJsonScraper.previewPathCandidates(number)

    suspend fun refreshMgstageNumberPrefixes(): Set<String> =
        remoteScrapeConfigRepository.syncRemoteNumberRecognitionRules()

    fun loadCachedNumberRecognitionRules(): Set<String> =
        remoteScrapeConfigRepository.loadCachedNumberRecognitionRules()

    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        var reachable = false
        logStore.append("Start Google connectivity check, timeout 5s")
        val elapsedMs = measureTimeMillis {
            reachable = networkProbe.canReachGoogle()
        }
        logStore.append("Google connectivity ${if (reachable) "passed" else "failed"}, elapsed=${elapsedMs}ms")
        reachable
    }

    suspend fun scrapeMovieWithOutput(movie: MovieEntity, source: ScrapeSource, forceDistinct: Boolean = false): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = extractMovieNumberWithRules(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")
        appendMovieDivider("影片刮削", number, target.file.name.orEmpty(), source)
        val taskId = taskJournal.start(movie.id, number, target.file.name.orEmpty(), "刮削", source.label)
        logStore.append("[刮削任务:$taskId] 开始：$number，来源=${source.label}")
        runCatching {
            scrapeTargetWithOutput(target, source, forceDistinct, taskId, dividerAlreadyLogged = true)
        }.onFailure { error ->
            if (error !is CancellationException) {
                val message = error.message ?: error::class.java.simpleName
                taskJournal.finish(taskId, success = false, message = message)
                logStore.append("[刮削任务:$taskId] 失败：$message")
            }
        }.getOrThrow()
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
        val target = requireStoredDocument(
            movieId = null,
            fileName = Uri.parse(strmUri).lastPathSegment.orEmpty(),
            databaseUri = strmUri,
            operation = "新增 STRM 刮削定位"
        ) {
            storedDocumentLocator.find(libraryRootUri, strmUri)?.toStrmTarget()
        }
        scrapeTargetWithOutput(target, source, forceDistinct, taskId = null)
    }

    private suspend fun scrapeTargetWithOutput(
        target: StrmTarget,
        source: ScrapeSource,
        forceDistinct: Boolean,
        taskId: String?,
        dividerAlreadyLogged: Boolean = false
    ): ScrapedMovieWriteResult {
        val number = extractMovieNumberWithRules(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")

        if (!dividerAlreadyLogged) {
            appendMovieDivider("影片刮削", number, target.file.name.orEmpty(), source)
        }
        return ScrapeLogContext.withMovieNumber(number) {
            taskId?.let { taskJournal.record(it, "识别", "已识别番号 $number；来源 ${source.label}") }
            val info = scrapeWithFallback(source, number, target.file.name.orEmpty())
            logStore.append("[影片刮削] 状态=元数据获取成功，番号=$number，标题=${info.title.ifBlank { number }}")
            taskId?.let { taskJournal.record(it, "请求", "元数据获取成功：${info.title.ifBlank { number }}", ScrapeEventLevel.Success) }
            val strmUri = writeOrganizedScrapeFiles(target, info, number, forceDistinct)
            downloadActorAvatars(info)
            logStore.append("[影片刮削] 状态=文件整理完成，番号=$number；说明=等待影片数据库同步")
            taskId?.let { taskJournal.record(it, "写入", "NFO 与图片已写入；等待数据库同步") }
            ScrapedMovieWriteResult(info = info, strmUri = strmUri, taskId = taskId)
        }
    }

    fun finishMovieScrapeTask(taskId: String?, success: Boolean, message: String) {
        taskId ?: return
        taskJournal.finish(taskId, success, message)
        logStore.append("[刮削任务:$taskId] ${if (success) "完成" else "失败"}：$message")
    }

    suspend fun rescrapeMovie(movie: MovieEntity, source: ScrapeSource): ScrapedMovieInfo =
        rescrapeMovieWithOutput(movie, source).info

    suspend fun rescrapeMovieWithOutput(movie: MovieEntity, source: ScrapeSource): RescrapedMovieWriteResult = runQueuedScrapeTask(
        label = "rescrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = extractMovieNumberWithRules(target.file.name.orEmpty(), movie.title)
            ?: error("无法从文件名提取番号：${target.file.name}")
        appendMovieDivider("影片重新刮削", number, target.file.name.orEmpty(), source)
        val taskId = taskJournal.start(movie.id, number, target.file.name.orEmpty(), "重新刮削", source.label)
        logStore.append("[刮削任务:$taskId] 开始重新刮削：$number，来源=${source.label}")
        runCatching {
            ScrapeLogContext.withMovieNumber(number) {
                taskJournal.record(taskId, "识别", "已识别番号 $number；来源 ${source.label}")
                val info = scrapeWithFallback(source, number, target.file.name.orEmpty())
                logStore.append("[影片重新刮削] 状态=元数据获取成功，番号=$number，标题=${info.title.ifBlank { number }}")
                taskJournal.record(taskId, "请求", "元数据获取成功：${info.title.ifBlank { number }}", ScrapeEventLevel.Success)
                rewriteScrapeFilesInPlace(target, info)
                downloadActorAvatars(info)
                logStore.append("[影片重新刮削] 状态=文件写入完成，番号=$number；说明=等待影片数据库刷新")
                taskJournal.record(taskId, "写入", "NFO 与图片已更新；等待数据库刷新")
                RescrapedMovieWriteResult(info, taskId)
            }
        }.onFailure { error ->
            if (error !is CancellationException) {
                val message = error.message ?: error::class.java.simpleName
                taskJournal.finish(taskId, success = false, message = message)
                logStore.append("[刮削任务:$taskId] 失败：$message")
            }
        }.getOrThrow()
    }

    private suspend fun scrapeWithFallback(
        requestedSource: ScrapeSource,
        number: String,
        fileName: String
    ): ScrapedMovieInfo {
        val sources = scrapeSourcesFor(requestedSource, number)
        val prefixRewriteRules = settingsRepository.getNumberPrefixRewriteRules()
        val outputNumber = rewriteNumberPrefix(number, prefixRewriteRules)
        val failures = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            dmm2SkipMessage(source, number)?.let { message ->
                failures += "${source.label}: $message"
                logStore.append("Priority scrape skipped ${source.label}: $message")
                return@forEachIndexed
            }
            if (requestedSource == ScrapeSource.Priority) {
                logStore.append("优先级刮削尝试 ${index + 1}/${sources.size}：${source.label}，file=$fileName，number=$number")
            }
            val sourceNumber = rewriteNumberPrefix(number, prefixRewriteRules, source)
            if (!sourceNumber.equals(number, ignoreCase = true)) {
                logStore.append("数字前缀规则命中：${source.label} 使用 $sourceNumber 查询")
            }
            val retryCount = settingsRepository.getScrapeRetryCount()
            runCatching {
                retryScrapeRequest(
                    attemptCount = retryCount,
                    onAttemptFailure = { attempt, error ->
                        logStore.append(
                            "刮削来源 ${source.label} 第 $attempt/$retryCount 次失败：${error.message ?: error::class.java.simpleName}"
                        )
                    }
                ) {
                    scrapeSource(source, sourceNumber, useSerialMutex = requestedSource == ScrapeSource.Priority)
                }
            }.onSuccess { info ->
                if (requestedSource == ScrapeSource.Priority) {
                    logStore.append("优先级刮削成功：${source.label} -> ${info.title.ifBlank { number }}")
                }
                return info.copy(
                    number = outputNumber,
                    source = info.source.ifBlank { source.label }
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: error::class.java.simpleName
                failures += "${source.label}: $message"
                if (requestedSource == ScrapeSource.Priority) {
                    logStore.append("优先级刮削失败：${source.label}，原因：$message")
                }
            }
        }
        if (requestedSource == ScrapeSource.Priority) {
            error("优先级刮削全部失败：${failures.joinToString("；")}")
        }
        error(failures.lastOrNull()?.substringAfter(": ") ?: "${requestedSource.label} 刮削失败")
    }

    private suspend fun scrapeSource(
        source: ScrapeSource,
        number: String,
        useSerialMutex: Boolean
    ): ScrapedMovieInfo {
        val scrapeNumber = if (source == ScrapeSource.Mgstage) {
            remoteScrapeConfigRepository.getMgstageNumberPrefixes()
            normalizeMgstageSearchNumber(
                number,
                remoteScrapeConfigRepository.getCachedMgstageSearchPrefixAliases()
            )
        } else {
            number
        }
        val mutex = source.serialScrapeMutex().takeIf { useSerialMutex }
        return if (mutex == null) {
            scraperRegistry.scrape(source, scrapeNumber)
        } else {
            mutex.withLock { scraperRegistry.scrape(source, scrapeNumber) }
        }
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        return extractNumberWithCachedRules(
            values = values.toList(),
            extractor = MovieNumberExtractor::extract,
            loadCachedRules = ::loadCachedNumberRecognitionRules
        )
    }

    private suspend fun extractOutputNumberWithRules(vararg values: String?): String? {
        val number = extractNumberWithCachedRules(
            values = values.toList(),
            extractor = MovieNumberExtractor::extractDisplayNumber,
            loadCachedRules = ::loadCachedNumberRecognitionRules
        )
        return number?.let { rewriteNumberPrefix(it, settingsRepository.getNumberPrefixRewriteRules()) }
    }

    private suspend fun scrapeSourcesFor(source: ScrapeSource, number: String): List<ScrapeSource> {
        if (source != ScrapeSource.Priority) return listOf(source)
        val configuredSources = settingsRepository.getPriorityScrapeSources()
        if (!settingsRepository.isMgstageAmateurPriorityEnabled()) return configuredSources
        val prefix = numberPrefix(number) ?: return configuredSources
        val mgstagePrefixes = remoteScrapeConfigRepository.getMgstageNumberPrefixes()
        if (prefix !in mgstagePrefixes) return configuredSources
        logStore.append("MGStage 前缀规则命中：$prefix，优先使用 MGStage")
        return (listOf(ScrapeSource.Mgstage) + configuredSources).distinct()
    }

    private fun dmm2SkipMessage(source: ScrapeSource, number: String): String? {
        if (source != ScrapeSource.Dmm2) return null
        val prefix = numberPrefix(number) ?: return null
        if (prefix.isBlank()) return null
        return if (prefix in settingsRepository.getDmm2SkippedNumberPrefixes()) {
            "DMM2不支持${prefix}番号刮削"
        } else {
            null
        }
    }

    suspend fun clearScrapeFiles(movie: MovieEntity): ClearScrapeResult = withContext(ioDispatcher) {
        val target = findTargetForMovie(movie)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
            ?: error("影片库目录不可用")
        val number = extractMovieNumberWithRules(target.file.name.orEmpty(), movie.title)
            ?: error("无法安全提取影片番号")

        logStore.append("Start clearing scrape files: $number")
        val restoredFileName = restoredStrmFileName(target.file, number)
        val directoryName = target.directory.name.orEmpty()
        val isOrganizedFolder = directoryName.hasSameMovieNumber(number) &&
            (directoryName.startsWith("\u3010") || directoryName.startsWith("[")) &&
            target.parentDirectory != null

        val restoredStrmUri = if (isOrganizedFolder) {
            val parent = target.parentDirectory ?: error("无法定位父目录")
            val rootFileName = root.uniqueChildFileName(restoredFileName)
            val restoredFile = copyStrmFile(target.file, root, rootFileName)
            logStore.append("STRM restored to library root: $rootFileName")
            deleteRecursively(target.directory)
            logStore.append("Deleted generated movie directory: ${target.directory.name}")
            deleteEmptyDirectory(parent, root)
            restoredFile.uri.toString()
        } else {
            deleteMetadataFiles(target.directory, target.baseName)
            logStore.append("Deleted metadata files for baseName=${target.baseName}")
            target.file.uri.toString()
        }

        logStore.append("Clear scrape files finished: $number")
        ClearScrapeResult(number = number, strmUri = restoredStrmUri)
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
        return requireStoredDocument(
            movieId = movie.id,
            fileName = movie.videoName,
            databaseUri = movie.videoUri,
            operation = "刮削定位"
        ) {
            storedDocumentLocator.find(movie.libraryRootUri, movie.videoUri)?.toStrmTarget()
        }
    }

    private fun LocatedDocument.toStrmTarget(): StrmTarget {
        val name = file.name.orEmpty()
        return StrmTarget(
            directory = directory,
            file = file,
            baseName = name.substringBeforeLast('.', name),
            parentDirectory = parentDirectory
        )
    }

    private suspend fun writeOrganizedScrapeFiles(target: StrmTarget, info: ScrapedMovieInfo, fallbackNumber: String, forceDistinct: Boolean = false): String {
        val sourceName = target.file.name.orEmpty()
        val outputNumber = extractOutputNumberWithRules(sourceName)
        val baseNumber = (outputNumber ?: fallbackNumber.ifBlank { info.number }).uppercase()
        val variant = detectMovieVariant(sourceName)
        val writeInfo = info.copy(number = baseNumber)
        if (info.number.isNotBlank() && !info.number.equals(baseNumber, ignoreCase = true)) {
            logStore.append("Use source filename number for output: $baseNumber (scraped=${info.number})")
        }
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
        logStore.append("[媒体路径] 状态=新文件已写入，操作=刮削整理，文件=$strmName，说明=等待数据库更新")

        try {
            val nfoName = "$baseName.nfo"
            logStore.append("Write NFO: $nfoName")
            writeTextFile(movieDirectory, nfoName, NfoWriter.build(writeInfo))
            logStore.append("NFO written: $nfoName")

            val imageReferer = info.imageReferer()
            val posterUrls = info.posterDownloadUrls()
            if (posterUrls.isNotEmpty()) {
                val posterName = "$baseName-poster.jpg"
                logStore.append("Download poster: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}")
                tryDownloadImageToFile(movieDirectory, posterName, posterUrls, imageReferer, "Poster")
            } else {
                logStore.append("Poster URL is blank; skipped")
            }

            val thumbUrls = info.thumbDownloadUrls()
            val fanartUrls = info.fanartDownloadUrls()
            if (thumbUrls.isNotEmpty()) {
                val thumbName = "$baseName-thumb.jpg"
                logStore.append("Download thumb: ${thumbUrls.first()}${thumbUrls.candidateCountSuffix()}")
                tryDownloadImageToFile(movieDirectory, thumbName, thumbUrls, imageReferer, "Thumb")

                val fanartName = "$baseName-fanart.jpg"
                logStore.append("Copy thumb as fanart: $fanartName")
                tryDownloadImageToFile(movieDirectory, fanartName, fanartUrls, imageReferer, "Fanart")
            } else {
                logStore.append("Thumb URL is blank; skipped")
            }
            deleteLegacyNfoXml(target)
            deleteOldStrmIfMoved(target, newStrm)
            return newStrm.uri.toString()
        } catch (error: Throwable) {
            rollbackCopiedStrm(target, newStrm)
            throw error
        }
    }

    private suspend fun rewriteScrapeFilesInPlace(target: StrmTarget, info: ScrapedMovieInfo) {
        val baseName = target.baseName
        val directory = target.directory
        val nfoName = "$baseName.nfo"
        val sourceNumber = extractOutputNumberWithRules(target.file.name.orEmpty()) ?: info.number
        val displayNumber = displayNumberWithVariant(sourceNumber, target.file.name.orEmpty())
        val writeInfo = info.copy(number = displayNumber)
        logStore.append("Rewrite NFO: $nfoName")
        writeTextFile(directory, nfoName, NfoWriter.build(writeInfo))
        logStore.append("NFO rewritten: $nfoName")

        val posterName = "$baseName-poster.jpg"
        val thumbName = "$baseName-thumb.jpg"
        val fanartName = "$baseName-fanart.jpg"
        val hasStandardPoster = directory.findFile(posterName) != null
        val hasPoster = hasStandardPoster || directory.hasAnyFile("poster.jpg", "movie-poster.jpg")
        val hasThumb = directory.hasAnyFile(thumbName, "thumb.jpg")
        val hasFanart = directory.hasAnyFile(fanartName, "fanart.jpg", "movie-fanart.jpg")
        val shouldRefreshPoster = info.shouldRefreshDistinctPoster()
        if (hasPoster && hasThumb && hasFanart && !shouldRefreshPoster) {
            logStore.append("poster/thumb/fanart already exist; skipped image downloads")
            deleteLegacyNfoXml(target)
            return
        }

        val imageReferer = info.imageReferer()
        val posterUrls = info.posterDownloadUrls()
        val thumbUrls = info.thumbDownloadUrls()
        val fanartUrls = info.fanartDownloadUrls()
        if (((!hasStandardPoster && posterUrls.isNotEmpty()) || (shouldRefreshPoster && posterUrls.isNotEmpty()))) {
            logStore.append(
                if (hasStandardPoster) {
                    "Poster refreshed: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}"
                } else {
                    "Poster missing; downloading: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}"
                }
            )
            tryDownloadImageToFile(directory, posterName, posterUrls, imageReferer, "Poster")
        }
        if (!hasThumb && thumbUrls.isNotEmpty()) {
            logStore.append("Thumb missing; downloading: ${thumbUrls.first()}${thumbUrls.candidateCountSuffix()}")
            tryDownloadImageToFile(directory, thumbName, thumbUrls, imageReferer, "Thumb")
        }
        if (!hasFanart && fanartUrls.isNotEmpty()) {
            logStore.append("Fanart missing; using thumb: $fanartName")
            tryDownloadImageToFile(directory, fanartName, fanartUrls, imageReferer, "Fanart")
        }
        deleteLegacyNfoXml(target)
    }

    private suspend fun downloadActorAvatars(info: ScrapedMovieInfo) {
        if (info.actorImageUrls.isEmpty()) return
        val imageReferer = info.imageReferer()
        info.actorImageUrls.forEach { (actorName, imageUrl) ->
            if (actorName.isBlank() || imageUrl.isBlank()) return@forEach
            if (actorAvatarStore.hasAvatar(actorName)) {
                logStore.append("Actor avatar already exists; skipped: $actorName")
                return@forEach
            }
            runCatching {
                logStore.append("Download actor avatar: $actorName -> $imageUrl")
                actorAvatarStore.saveAvatar(actorName, imageDownloadService.downloadImageBytes(imageUrl, imageReferer))
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

    private fun restoredStrmFileName(source: DocumentFile, fallbackNumber: String): String {
        val content = context.contentResolver.openInputStream(source.uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        val originalName = content.extractOriginalVideoNameFromStrm()
            ?: source.name
                ?.takeIf { !it.startsWith("\u3010") && !it.startsWith("[") }
        val baseName = originalName
            ?.substringBeforeLast('.', originalName)
            ?.sanitizeFileName()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackNumber.sanitizeFileName()
        return "$baseName.strm"
    }

    private fun String.extractOriginalVideoNameFromStrm(): String? {
        val uri = runCatching { Uri.parse(this.trim()) }.getOrNull() ?: return null
        val segments = uri.pathSegments
        val routeIndex = segments.indexOfFirst { it == "download_m3u" || it == "play" || it == "video_proxy" }
        return routeIndex
            .takeIf { it >= 0 && it + 2 < segments.size }
            ?.let { segments[it + 2] }
            ?.let(Uri::decode)
            ?.takeIf { it.isNotBlank() }
    }

    private fun deleteOldStrmIfMoved(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (target.file.delete()) {
            logStore.append(
                "[媒体路径] 状态=文件移动完成，操作=刮削整理，文件=${newStrm.name}，详情=旧 STRM 已删除；等待调用方同步数据库"
            )
        } else {
            logStore.append(
                "[媒体路径] 状态=警告，操作=刮削整理，文件=${target.file.name}，详情=新 STRM 已写入，但旧 STRM 删除失败；数据库将指向新文件"
            )
        }
    }

    private fun rollbackCopiedStrm(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (newStrm.delete()) {
            logStore.append("Rollback copied STRM after scrape file write failure: ${newStrm.name}")
        } else {
            logStore.append("Warning: failed to rollback copied STRM: ${newStrm.name}")
        }
    }

    private fun deleteLegacyNfoXml(target: StrmTarget) {
        val legacyName = "${target.baseName}.nfo.xml"
        target.directory.findFile(legacyName)?.let { legacy ->
            if (legacy.delete()) {
                logStore.append("Deleted legacy NFO file: $legacyName")
            }
        }
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

    private fun DocumentFile.uniqueChildFileName(desiredName: String): String {
        if (findFile(desiredName) == null) return desiredName
        val baseName = desiredName.substringBeforeLast('.', desiredName)
        val extension = desiredName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (true) {
            val candidate = buildString {
                append(baseName)
                append("-")
                append(index)
                extension?.let { append(".").append(it) }
            }
            if (findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private suspend fun downloadImageToFile(directory: DocumentFile, fileName: String, url: String, referer: String? = null) {
        val bytes = imageDownloadService.downloadImageBytes(url, referer)
        directory.findFile(fileName)?.delete()
        val file = directory.createFile("image/jpeg", fileName)
            ?: error("无法创建图片：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: error("无法写入图片：$fileName")
    }

    private suspend fun tryDownloadImageToFile(
        directory: DocumentFile,
        fileName: String,
        url: String,
        referer: String?,
        label: String
    ): Boolean = tryDownloadImageToFile(directory, fileName, listOf(url), referer, label)

    private suspend fun tryDownloadImageToFile(
        directory: DocumentFile,
        fileName: String,
        urls: List<String>,
        referer: String?,
        label: String
    ): Boolean {
        val candidates = urls.normalizedUrlList()
        if (candidates.isEmpty()) return false
        candidates.forEachIndexed { index, url ->
            runCatching {
                downloadImageToFile(directory, fileName, url, referer)
            }.onSuccess {
                if (index > 0) {
                    logStore.append("$label fallback succeeded (${index + 1}/${candidates.size}): $url")
                }
                logStore.append("$label written: $fileName")
                return true
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                if (index < candidates.lastIndex) {
                    logStore.append("$label download failed (${index + 1}/${candidates.size}), trying next: $message")
                } else {
                    logStore.append("$label download failed; skipped image: $message")
                }
            }
        }
        return false
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

    private fun deleteEmptyDirectory(directory: DocumentFile, root: DocumentFile) {
        if (directory.uri == root.uri) return
        if (!directory.isDirectory) return
        if (directory.listFiles().isEmpty() && directory.delete()) {
            logStore.append("Deleted empty parent directory: ${directory.name}")
        }
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun String.hasSameMovieNumber(number: String): Boolean {
        val expected = movieKeyFromText(number) ?: number.uppercase()
        return movieKeyFromText(this) == expected || contains(number, ignoreCase = true)
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

    private fun ScrapedMovieInfo.imageReferer(): String? {
        if (source.equals("mgstage", ignoreCase = true)) return MGSTAGE_BASE_URL
        return website
            .takeIf { source.equals("javbus", ignoreCase = true) }
            ?.takeIf { it.startsWith(JAVBUS_BASE_URL, ignoreCase = true) }
    }

    private fun ScrapedMovieInfo.posterDownloadUrls(): List<String> =
        (posterImageUrls + posterUrl.ifBlank { thumbUrl }).normalizedUrlList()

    private fun ScrapedMovieInfo.thumbDownloadUrls(): List<String> =
        (thumbImageUrls + thumbUrl).normalizedUrlList()

    private fun ScrapedMovieInfo.fanartDownloadUrls(): List<String> =
        (listOf(fanartUrl) + thumbImageUrls + thumbUrl).normalizedUrlList()

    private fun ScrapedMovieInfo.shouldRefreshDistinctPoster(): Boolean {
        if (!source.equals("dmm", ignoreCase = true) && !source.equals("dmm2", ignoreCase = true)) return false
        val poster = posterDownloadUrls().firstOrNull().orEmpty()
        val thumb = thumbDownloadUrls().firstOrNull().orEmpty()
        return poster.isNotBlank() && thumb.isNotBlank() && !poster.equals(thumb, ignoreCase = true)
    }

    private fun List<String>.normalizedUrlList(): List<String> =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    private fun List<String>.candidateCountSuffix(): String =
        if (size > 1) " (+${size - 1} fallback)" else ""

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
            ScrapeSource.Priority -> "优先级刮削"
            ScrapeSource.Dmm -> "DMM"
            ScrapeSource.Dmm2 -> "DMM2"
            ScrapeSource.Official -> "Official"
            ScrapeSource.Mgstage -> "MGStage"
            ScrapeSource.Javbus -> "JavBus"
            ScrapeSource.TheJavDB -> "TheJavDB"
            ScrapeSource.CustomJson -> "自定义 JSON"
        }

    private fun ScrapeSource.serialScrapeMutex(): Mutex? = null

    private fun numberPrefix(number: String): String? =
        number.substringBefore('-', missingDelimiterValue = number)
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }

    private companion object {
        const val GENERIC_FILE_MIME_TYPE = "application/octet-stream"
        const val JAVBUS_BASE_URL = "https://www.javbus.com/"
        const val MGSTAGE_BASE_URL = "https://www.mgstage.com/"
        const val SCRAPE_QUEUE_POLL_INTERVAL_MS = 250L
    }
}

internal suspend fun <T> retryScrapeRequest(
    attemptCount: Int,
    retryDelayMillis: Long = 1_000L,
    onAttemptFailure: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T
): T {
    require(attemptCount > 0) { "attemptCount must be positive" }
    var lastError: Throwable? = null
    repeat(attemptCount) { index ->
        val attempt = index + 1
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            onAttemptFailure(attempt, error)
            if (attempt < attemptCount && retryDelayMillis > 0L) {
                delay(retryDelayMillis)
            }
        }
    }
    throw lastError ?: IllegalStateException("刮削失败")
}

internal fun extractNumberWithCachedRules(
    values: List<String?>,
    extractor: (String) -> String?,
    loadCachedRules: () -> Unit
): String? {
    val candidates = values.mapNotNull { it?.takeIf(String::isNotBlank) }
    loadCachedRules()
    candidates.forEach { value -> extractor(value)?.let { return it } }
    return null
}

data class ActorAvatarUpdateResult(
    val totalMissing: Int,
    val downloaded: Int,
    val scrapedMovies: Int
)

data class ScrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val strmUri: String,
    val taskId: String? = null
)

data class RescrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val taskId: String
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
