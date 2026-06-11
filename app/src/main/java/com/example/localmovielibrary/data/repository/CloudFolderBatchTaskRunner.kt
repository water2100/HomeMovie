package com.example.localmovielibrary.data.repository

import android.net.Uri
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class CloudFolderBatchTaskRunner(
    private val taskRepository: CloudFolderBatchTaskRepository,
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val addLocks = mutableMapOf<String, Mutex>()
    private val _isRunning = MutableStateFlow(false)
    private var job: Job? = null
    private var currentTaskId: Long? = null

    val isRunning: StateFlow<Boolean> = _isRunning

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runMutex.withLock {
                _isRunning.value = true
                try {
                    runPendingTasks()
                } finally {
                    currentTaskId = null
                    _isRunning.value = false
                }
            }
        }
    }

    fun stop() {
        job?.cancel(CancellationException("已手动停止文件夹刮削任务"))
    }

    private suspend fun runPendingTasks() {
        while (true) {
            val task = taskRepository.nextRunnableTask() ?: return
            currentTaskId = task.id
            runCatching {
                runTask(task)
            }.onFailure { error ->
                if (error is CancellationException) {
                    taskRepository.markPaused(task.id, "已手动停止")
                    scrapeRepository.appendLog("网盘文件夹任务已停止：${task.folderName}")
                    throw error
                }
                val message = error.message ?: error::class.java.simpleName
                taskRepository.markFailed(task.id, message)
                scrapeRepository.appendLog("网盘文件夹任务失败：${task.folderName} / ${task.folderCid}，原因：$message")
            }
            currentTaskId = null
        }
    }

    private suspend fun runTask(task: com.example.localmovielibrary.data.local.CloudFolderBatchTaskEntity) {
        taskRepository.resetForRun(task.id) ?: return
        val summary = CloudFolderBatchRunSummary(folderName = task.folderName)
        val candidates = mutableListOf<CloudFolderVideoCandidate>()
        val numberChecker = CloudAddNumberChecker()
        val folder = Cloud115FileItem(
            name = task.folderName,
            cid = task.folderCid,
            fid = null,
            pickcode = null,
            size = null,
            modifiedAt = null,
            isDirectory = true
        )

        scrapeRepository.appendLog("网盘文件夹任务开始：${task.folderName} / ${task.folderCid}")
        collectFolderVideoCandidates(
            taskId = task.id,
            folder = folder,
            path = task.folderName,
            excludedVideoNames = settingsRepository.getCloudExcludedVideoNames(),
            skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes(),
            candidates = candidates,
            summary = summary,
            numberChecker = numberChecker,
            delayBeforeRequest = false
        )
        summary.queuedVideos = candidates.size
        persistProgress(task.id, summary, currentPath = task.folderName, currentFileName = null)
        scrapeRepository.appendLog(
            "网盘文件夹任务收集完成：${task.folderName}，候选=${summary.queuedVideos}，跳过=${summary.skippedVideos}，子文件夹失败=${summary.failedFolders}"
        )

        val existingPickcodes = recordRepository.existingPickcodesForVisibleItems(
            candidates.mapNotNull { it.item.pickcode?.takeIf(String::isNotBlank) }.toSet()
        )
        if (existingPickcodes.isNotEmpty()) {
            summary.skippedVideos += existingPickcodes.size
            persistProgress(task.id, summary, currentPath = task.folderName, currentFileName = null)
            scrapeRepository.appendLog("网盘文件夹任务跳过已存在视频：${existingPickcodes.size} 个 pickcode 已在影片库记录中")
        }

        candidates.forEachIndexed { index, candidate ->
            val item = candidate.item
            val pickcode = item.pickcode?.takeIf(String::isNotBlank)
            persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            if (pickcode == null) {
                summary.skippedVideos += 1
                summary.processedVideos = index + 1
                scrapeRepository.appendLog("网盘文件夹任务跳过：${candidate.fullPath}，原因：缺少 pickcode")
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
                return@forEachIndexed
            }
            if (pickcode in existingPickcodes) {
                summary.processedVideos = index + 1
                scrapeRepository.appendLog("网盘文件夹任务跳过：${candidate.fullPath}，原因：视频已添加")
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
                return@forEachIndexed
            }
            runCatching {
                withAddLock(item.name) {
                    processCloudVideoAddAllowScrapeFailure(item, pickcode, forceDistinct = false)
                }
            }.onSuccess { result ->
                summary.addedVideos += 1
                if (result.scrapeFailed) summary.scrapeFailedVideos += 1
                summary.processedVideos = index + 1
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: error::class.java.simpleName
                summary.failedVideos += 1
                summary.processedVideos = index + 1
                summary.rememberFailure("${item.name}: $message")
                scrapeRepository.appendLog("网盘文件夹任务失败：${item.name} / $pickcode，原因：$message")
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            }
        }

        val message = summary.toUserMessage()
        taskRepository.markCompleted(task.id, summary.toProgress(task.folderName, null), message)
        scrapeRepository.appendLog("网盘文件夹任务完成：${task.folderName}，$message")
    }

    private suspend fun collectFolderVideoCandidates(
        taskId: Long,
        folder: Cloud115FileItem,
        path: String,
        excludedVideoNames: Set<String>,
        skipBelowSizeBytes: Long,
        candidates: MutableList<CloudFolderVideoCandidate>,
        summary: CloudFolderBatchRunSummary,
        numberChecker: CloudAddNumberChecker,
        delayBeforeRequest: Boolean
    ) {
        val cid = folder.cid ?: run {
            summary.failedFolders += 1
            summary.rememberFailure("$path: 文件夹缺少 CID")
            persistProgress(taskId, summary, currentPath = path, currentFileName = null)
            return
        }
        persistProgress(taskId, summary, currentPath = path, currentFileName = null)
        val children = listFolderChildrenWithRetry(
            cid = cid,
            path = path,
            summary = summary,
            taskId = taskId,
            delayBeforeRequest = delayBeforeRequest
        ) ?: return

        children
            .asSequence()
            .filter { !it.isDirectory && it.isVideoFile() }
            .sortedBy { it.name.lowercase() }
            .forEach { item ->
                val reason = item.batchSkipReason(excludedVideoNames, skipBelowSizeBytes)
                    ?: if (numberChecker.extract(item.name) == null) "无法提取番号" else null
                if (reason == null) {
                    candidates += CloudFolderVideoCandidate(item = item, path = path)
                } else {
                    summary.skippedVideos += 1
                    scrapeRepository.appendLog("网盘文件夹任务跳过：$path/${item.name}，原因：$reason")
                    persistProgress(taskId, summary, currentPath = path, currentFileName = item.name)
                }
            }

        children
            .asSequence()
            .filter { it.isDirectory && it.cid != null }
            .sortedBy { it.name.lowercase() }
            .forEach { child ->
                collectFolderVideoCandidates(
                    taskId = taskId,
                    folder = child,
                    path = "$path/${child.name}",
                    excludedVideoNames = excludedVideoNames,
                    skipBelowSizeBytes = skipBelowSizeBytes,
                    candidates = candidates,
                    summary = summary,
                    numberChecker = numberChecker,
                    delayBeforeRequest = true
                )
            }
    }

    private suspend fun listFolderChildrenWithRetry(
        cid: Long,
        path: String,
        summary: CloudFolderBatchRunSummary,
        taskId: Long,
        delayBeforeRequest: Boolean
    ): List<Cloud115FileItem>? {
        var attempt = 1
        while (true) {
            if (delayBeforeRequest) {
                delay(Random.nextLong(CHILD_FOLDER_LIST_DELAY_MIN_MS, CHILD_FOLDER_LIST_DELAY_MAX_MS + 1))
            }
            runCatching {
                return strmRepository.listFiles(cid)
            }.onFailure { error ->
                if (!delayBeforeRequest) throw error
                val message = error.message ?: error::class.java.simpleName
                if (attempt >= CHILD_FOLDER_LIST_MAX_ATTEMPTS) {
                    summary.failedFolders += 1
                    summary.rememberFailure("$path: $message")
                    scrapeRepository.appendLog("读取子文件夹失败：$path / $cid，已跳过该子文件夹，原因：$message")
                    persistProgress(taskId, summary, currentPath = path, currentFileName = null)
                    return null
                }
                val retryDelay = Random.nextLong(CHILD_FOLDER_RETRY_DELAY_MIN_MS, CHILD_FOLDER_RETRY_DELAY_MAX_MS + 1)
                scrapeRepository.appendLog("读取子文件夹失败：$path / $cid，第 $attempt 次，${retryDelay / 1000.0} 秒后重试，原因：$message")
                delay(retryDelay)
                attempt += 1
            }
        }
    }

    private suspend fun processCloudVideoAddAllowScrapeFailure(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): CloudFolderVideoAddResult {
        scrapeRepository.appendLog("开始处理网盘文件夹任务：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独刮削：${generated.fileName}")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
            )
        }

        scrapeRepository.appendLog("网盘文件夹任务 STRM 写入完成，开始扫描：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        addedMovie?.let { recordRepository.attachMovie(pickcode, it.id) }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            scrapeRepository.appendLog("网盘文件夹任务未刮削：${item.name}，原因：$reason")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已添加但未刮削：${item.name}",
                scrapeFailed = true
            )
        }

        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(pickcode, movieForScrape.id)
        movieRepository.markScrapeTaskPending(movieForScrape.id)
        val source = settingsRepository.getDefaultScrapeSource()
        scrapeRepository.appendLog("开始网盘文件夹任务刮削影片：$number，来源：$source")
        movieRepository.markScrapeTaskRunning(movieForScrape.id)
        val scrapeResult = runCatching {
            scrapeRepository.scrapeStrmUriWithOutput(
                libraryRootUri = libraryRoot,
                strmUri = generated.strmUri,
                source = source,
                forceDistinct = generated.forceDistinct
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
            movieRepository.markScrapeTaskFailed(movieForScrape.id, reason)
            scrapeRepository.appendLog("网盘文件夹任务已入库但刮削失败：$number，原因：$reason")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已添加但刮削失败：$number",
                scrapeFailed = true
            )
        }

        scrapeRepository.appendLog("网盘文件夹任务刮削完成，开始刷新单个影片：$number")
        val refreshedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(scrapeResult.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        if (refreshedMovie != null) {
            if (
                addedMovie != null &&
                addedMovie.id != refreshedMovie.id &&
                addedMovie.videoUri != refreshedMovie.videoUri
            ) {
                movieRepository.deleteMovie(addedMovie.id)
                scrapeRepository.appendLog("已删除网盘文件夹任务刮削前临时入库记录，避免重复显示：${addedMovie.videoName}")
            }
            movieRepository.markScrapeTaskCompleted(refreshedMovie.id)
            recordRepository.updateStrmLocation(
                pickcode = pickcode,
                strmUri = refreshedMovie.videoUri,
                libraryRootUri = refreshedMovie.libraryRootUri,
                movieId = refreshedMovie.id
            )
        } else {
            movieRepository.markScrapeTaskCompleted(movieForScrape.id)
            scrapeRepository.appendLog("网盘文件夹任务刮削后未定位到整理后的 STRM，已清空原影片失败标记：$number")
        }
        return CloudFolderVideoAddResult(
            pickcode = pickcode,
            message = if (generated.created) {
                "已添加并刮削：$number"
            } else {
                "STRM 已存在，已刷新并刮削：$number"
            }
        )
    }

    private suspend fun <T> withAddLock(fileName: String, block: suspend () -> T): T {
        val key = extractMovieNumberWithRules(fileName)?.uppercase() ?: fileName.trim().lowercase()
        val lock = synchronized(addLocks) {
            addLocks.getOrPut(key) { Mutex() }
        }
        if (lock.isLocked) {
            scrapeRepository.appendLog("同番号文件夹任务等待整理完成：$key")
        }
        return lock.withLock {
            block()
        }
    }

    private suspend fun persistProgress(
        taskId: Long,
        summary: CloudFolderBatchRunSummary,
        currentPath: String?,
        currentFileName: String?
    ) {
        taskRepository.updateProgress(taskId, summary.toProgress(currentPath, currentFileName))
    }

    private inner class CloudAddNumberChecker {
        private var loadedCachedRules = false
        private var forcedRefreshTried = false

        suspend fun extract(fileName: String): String? {
            if (!loadedCachedRules) {
                scrapeRepository.refreshNumberRecognitionRules(forceRefresh = false)
                loadedCachedRules = true
            }
            MovieNumberExtractor.extract(fileName)?.let { return it }
            if (!forcedRefreshTried) {
                scrapeRepository.refreshNumberRecognitionRules(forceRefresh = true)
                forcedRefreshTried = true
            }
            return MovieNumberExtractor.extract(fileName)
        }
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        scrapeRepository.refreshNumberRecognitionRules(forceRefresh = false)
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        scrapeRepository.refreshNumberRecognitionRules(forceRefresh = true)
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        return null
    }

    companion object {
        private const val CHILD_FOLDER_LIST_DELAY_MIN_MS = 1_500L
        private const val CHILD_FOLDER_LIST_DELAY_MAX_MS = 3_000L
        private const val CHILD_FOLDER_RETRY_DELAY_MIN_MS = 2_000L
        private const val CHILD_FOLDER_RETRY_DELAY_MAX_MS = 5_000L
        private const val CHILD_FOLDER_LIST_MAX_ATTEMPTS = 3
    }
}

private data class CloudFolderVideoCandidate(
    val item: Cloud115FileItem,
    val path: String
) {
    val fullPath: String = "$path/${item.name}"
}

private data class CloudFolderVideoAddResult(
    val pickcode: String,
    val message: String,
    val scrapeFailed: Boolean = false
)

private data class CloudFolderBatchRunSummary(
    val folderName: String,
    var queuedVideos: Int = 0,
    var processedVideos: Int = 0,
    var addedVideos: Int = 0,
    var scrapeFailedVideos: Int = 0,
    var skippedVideos: Int = 0,
    var failedVideos: Int = 0,
    var failedFolders: Int = 0,
    val failureSamples: MutableList<String> = mutableListOf()
) {
    fun rememberFailure(message: String) {
        if (failureSamples.size < 3) {
            failureSamples += message
        }
    }

    fun toProgress(currentPath: String?, currentFileName: String?): CloudFolderBatchTaskProgress =
        CloudFolderBatchTaskProgress(
            currentPath = currentPath,
            currentFileName = currentFileName,
            queuedVideos = queuedVideos,
            processedVideos = processedVideos,
            addedVideos = addedVideos,
            scrapeFailedVideos = scrapeFailedVideos,
            skippedVideos = skippedVideos,
            failedVideos = failedVideos,
            failedFolders = failedFolders,
            failureMessage = failureSamples.firstOrNull()
        )

    fun toUserMessage(): String {
        if (queuedVideos == 0 && addedVideos == 0) {
            return buildString {
                append("文件夹没有可添加的视频，已跳过 $skippedVideos 个")
                if (failedFolders > 0) append("，$failedFolders 个子文件夹读取失败")
                if (failedVideos > 0) append("，$failedVideos 个视频失败")
            }
        }
        return buildString {
            append("入库 $addedVideos 个")
            if (scrapeFailedVideos > 0) append("，$scrapeFailedVideos 个未刮削成功")
            if (skippedVideos > 0) append("，跳过 $skippedVideos 个")
            if (failedVideos > 0 || failedFolders > 0) append("，失败 ${failedVideos + failedFolders} 项")
        }
    }
}

private fun Cloud115FileItem.batchSkipReason(
    excludedVideoNames: Set<String>,
    skipBelowSizeBytes: Long
): String? {
    if (name.trim() in excludedVideoNames) return "命中网盘排除名单"
    if (pickcode.isNullOrBlank()) return "缺少 pickcode"
    val sizeBytes = size
    if (skipBelowSizeBytes > 0L && sizeBytes != null && sizeBytes <= skipBelowSizeBytes) {
        return "文件大小 ${formatCloudSize(sizeBytes)} 小于或等于阈值 ${formatCloudSize(skipBelowSizeBytes)}"
    }
    return null
}

private fun Cloud115FileItem.isVideoFile(): Boolean =
    CLOUD_VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

private fun formatCloudSize(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = size.toDouble()
    var index = 0
    while (amount >= 1024.0 && index < units.lastIndex) {
        amount /= 1024.0
        index += 1
    }
    return "%.1f %s".format(amount, units[index])
}

private val CLOUD_VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".ts", ".iso", ".flv", ".webm")
