package com.example.localmovielibrary.ui.cloud

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.Cloud115StrmRepository
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRepository
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRunner
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

class CloudBrowserViewModel(
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val domesticMovieRepository: DomesticMovieRepository,
    private val folderBatchTaskRepository: CloudFolderBatchTaskRepository,
    private val folderBatchTaskRunner: CloudFolderBatchTaskRunner
) : ViewModel() {
    private val backStack = mutableListOf(CloudPathItem(ROOT_CID, ROOT_NAME))
    private val _uiState = MutableStateFlow(CloudBrowserUiState(path = backStack.toList(), isLoading = true))
    val uiState: StateFlow<CloudBrowserUiState> = _uiState
    private var loadJob: Job? = null
    private val scrollPositions = mutableMapOf<Long, CloudScrollPosition>()
    private val addLocks = mutableMapOf<String, Mutex>()
    private val folderBatchQueueLock = Any()
    private val folderBatchQueue = ArrayDeque<PendingFolderBatchAdd>()
    private var isFolderBatchRunning = false

    init {
        loadCurrent()
    }

    fun domesticRootCid(): Long? = settingsRepository.getDomesticRootCid()

    fun isCloudAddButtonMessageEnabled(): Boolean =
        settingsRepository.isCloudAddButtonMessageEnabled()

    private fun progressMessage(text: String?): String? =
        if (settingsRepository.isCloudAddButtonMessageEnabled()) text else null

    fun openFolder(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        backStack += CloudPathItem(cid, item.name)
        loadCurrent()
    }

    fun goBackFolder(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        loadCurrent()
        return true
    }

    fun canGoBackFolder(): Boolean = backStack.size > 1

    fun refresh() {
        loadCurrent()
    }

    fun resetToRoot() {
        if (backStack.size == 1 && backStack.first().cid == ROOT_CID) {
            loadCurrent()
            return
        }
        backStack.clear()
        backStack += CloudPathItem(ROOT_CID, ROOT_NAME)
        scrollPositions.clear()
        _uiState.update {
            it.copy(
                path = backStack.toList(),
                scrollResetVersion = it.scrollResetVersion + 1
            )
        }
        loadCurrent()
    }

    fun scrollPositionFor(cid: Long): CloudScrollPosition =
        scrollPositions[cid] ?: CloudScrollPosition()

    fun saveScrollPosition(cid: Long, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        scrollPositions[cid] = CloudScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset
        )
    }

    fun addDomesticFolder(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        if (cid in _uiState.value.addingDomesticFolderCids || cid in _uiState.value.addedDomesticFolderCids) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    addingDomesticFolderCids = it.addingDomesticFolderCids + cid,
                    message = progressMessage("正在添加国产目录：${item.name}")
                )
            }
            runCatching { domesticMovieRepository.addFolder(item) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            addingDomesticFolderCids = state.addingDomesticFolderCids - cid,
                            addedDomesticFolderCids = state.addedDomesticFolderCids + cid,
                            message = progressMessage("已添加到国产：${item.name}")
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            addingDomesticFolderCids = state.addingDomesticFolderCids - cid,
                            message = error.message ?: "国产目录添加失败"
                        )
                    }
                }
        }
    }

    fun toggleSortDirection() {
        val current = _uiState.value
        val nextAscending = !current.sortAscending
        val currentFolderCid = current.path.lastOrNull()?.cid ?: 0L
        scrollPositions[currentFolderCid] = CloudScrollPosition()
        _uiState.update {
            it.copy(sortAscending = nextAscending)
        }
        viewModelScope.launch {
            val sortedItems = withContext(Dispatchers.Default) {
                current.items.sortedByCloudOption(current.sortOption, nextAscending)
            }
            _uiState.update {
                if (it.sortAscending == nextAscending) {
                    it.copy(
                        items = sortedItems,
                        scrollResetVersion = it.scrollResetVersion + 1
                    )
                } else {
                    it
                }
            }
        }
    }

    fun setSortOption(option: CloudSortOption) {
        val current = _uiState.value
        if (current.sortOption == option) return
        val currentFolderCid = current.path.lastOrNull()?.cid ?: 0L
        scrollPositions[currentFolderCid] = CloudScrollPosition()
        _uiState.update { it.copy(sortOption = option) }
        viewModelScope.launch {
            val sortedItems = withContext(Dispatchers.Default) {
                current.items.sortedByCloudOption(option, current.sortAscending)
            }
            _uiState.update {
                if (it.sortOption == option) {
                    it.copy(
                        items = sortedItems,
                        scrollResetVersion = it.scrollResetVersion + 1
                    )
                } else {
                    it
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeOpenMovie() {
        _uiState.update { it.copy(openMovieId = null) }
    }

    fun addVideoToLibrary(item: Cloud115FileItem) {
        addVideoToLibraryWithConflictCheck(item)
    }

    fun addFolderVideosToLibrary(folder: Cloud115FileItem) {
        val cid = folder.cid ?: run {
            _uiState.update { it.copy(message = "这个文件夹没有 CID，无法批量添加") }
            return
        }
        viewModelScope.launch {
            runCatching {
                folderBatchTaskRepository.enqueueFolder(folder)
            }.onSuccess {
                scrapeRepository.appendLog("网盘文件夹任务已加入：${folder.name} / $cid")
                folderBatchTaskRunner.start()
                _uiState.update {
                    it.copy(
                        message = progressMessage("已加入网盘文件夹任务，可到设置-刮削队列查看进度：${folder.name}")
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        message = error.message ?: "文件夹任务创建失败"
                    )
                }
            }
        }
    }

    private fun startNextFolderBatchAdd() {
        val pending = synchronized(folderBatchQueueLock) {
            if (isFolderBatchRunning) {
                null
            } else {
                folderBatchQueue.removeFirstOrNull()?.also {
                    isFolderBatchRunning = true
                }
            }
        } ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    message = progressMessage("正在读取文件夹：${pending.folder.name}${folderBatchWaitingSuffix()}")
                )
            }
            var continueQueue = true
            try {
                val summary = withContext(Dispatchers.IO) {
                    processFolderBatchAdd(pending)
                }
                _uiState.update { state ->
                    state.copy(
                        message = progressMessage(summary.toUserMessage())
                    )
                }
            } catch (error: CancellationException) {
                continueQueue = false
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "文件夹批量添加失败"
                scrapeRepository.appendLog("网盘文件夹批量添加失败：${pending.folder.name} / ${pending.cid}，原因：$message")
                _uiState.update {
                    it.copy(
                        message = message
                    )
                }
            } finally {
                synchronized(folderBatchQueueLock) {
                    isFolderBatchRunning = false
                }
                if (continueQueue) {
                    startNextFolderBatchAdd()
                }
            }
        }
    }

    private suspend fun processFolderBatchAdd(pending: PendingFolderBatchAdd): CloudFolderBatchSummary {
        val folder = pending.folder
        val summary = CloudFolderBatchSummary(folderName = folder.name)
        val candidates = mutableListOf<Cloud115FileItem>()
        val numberChecker = CloudAddNumberChecker()
        collectFolderVideoCandidates(
            folder = folder,
            path = folder.name,
            excludedVideoNames = settingsRepository.getCloudExcludedVideoNames(),
            skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes(),
            candidates = candidates,
            summary = summary,
            numberChecker = numberChecker,
            delayBeforeRequest = false
        )
        summary.queuedVideos = candidates.size
        scrapeRepository.appendLog(
            "网盘文件夹批量添加收集完成：${folder.name}，候选=${summary.queuedVideos}，跳过=${summary.skippedVideos}"
        )
        val existingPickcodes = recordRepository.existingPickcodesForVisibleItems(
            candidates.mapNotNull { it.pickcode?.takeIf { pickcode -> pickcode.isNotBlank() } }.toSet()
        )
        if (existingPickcodes.isNotEmpty()) {
            summary.skippedVideos += existingPickcodes.size
            scrapeRepository.appendLog(
                "批量添加跳过已存在视频：${existingPickcodes.size} 个 pickcode 已在影片库记录中"
            )
            _uiState.update {
                it.copy(addedPickcodes = it.addedPickcodes + existingPickcodes)
            }
        }
        candidates.forEachIndexed { index, item ->
            val pickcode = item.pickcode?.takeIf { it.isNotBlank() }
            if (pickcode == null) {
                summary.skippedVideos += 1
                scrapeRepository.appendLog("批量添加跳过：${item.name}，原因：缺少 pickcode")
                return@forEachIndexed
            }
            if (pickcode in existingPickcodes) {
                scrapeRepository.appendLog("批量添加跳过：${item.name}，原因：视频已添加")
                return@forEachIndexed
            }
            _uiState.update {
                it.copy(
                    addingPickcodes = it.addingPickcodes + pickcode,
                    message = progressMessage("批量添加 ${index + 1}/${summary.queuedVideos}：${item.name}${folderBatchWaitingSuffix()}")
                )
            }
            runCatching {
                withAddLock(item.name) {
                    processCloudVideoAddAllowScrapeFailure(item, pickcode, forceDistinct = false)
                }
            }.onSuccess { result ->
                summary.addedVideos += 1
                if (result.scrapeFailed) summary.scrapeFailedVideos += 1
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        addedPickcodes = it.addedPickcodes + pickcode
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: error::class.java.simpleName
                summary.failedVideos += 1
                summary.rememberFailure("${item.name}: $message")
                scrapeRepository.appendLog("批量添加失败：${item.name} / $pickcode，原因：$message")
                _uiState.update {
                    it.copy(addingPickcodes = it.addingPickcodes - pickcode)
                }
            }
        }
        return summary
    }

    private fun folderBatchWaitingSuffix(): String {
        val waitingCount = synchronized(folderBatchQueueLock) { folderBatchQueue.size }
        return if (waitingCount > 0) "（后续 $waitingCount 个文件夹）" else ""
    }

    private fun addVideoToLibraryWithConflictCheck(item: Cloud115FileItem) {
        val pickcode = item.pickcode
        if (pickcode.isNullOrBlank()) {
            _uiState.update { it.copy(message = "这个视频没有 pickcode，无法添加") }
            return
        }
        if (pickcode in _uiState.value.addedPickcodes || pickcode in _uiState.value.addingPickcodes) {
            _uiState.update { it.copy(message = "这个视频已经添加或正在添加") }
            return
        }
        _uiState.update {
            it.copy(
                addingPickcodes = it.addingPickcodes + pickcode,
                message = progressMessage("正在检查 ${item.name}")
            )
        }
        viewModelScope.launch {
            try {
                if (recordRepository.get(pickcode) != null) {
                    _uiState.update {
                        it.copy(
                            addingPickcodes = it.addingPickcodes - pickcode,
                            addedPickcodes = it.addedPickcodes + pickcode,
                            message = "影片已经添加"
                        )
                    }
                    return@launch
                }
                val number = withContext(Dispatchers.IO) {
                    CloudAddNumberChecker().extract(item.name)
                }
                if (number == null) {
                    scrapeRepository.appendLog("网盘添加跳过：${item.name}，原因：无法提取番号")
                    _uiState.update {
                        it.copy(
                            addingPickcodes = it.addingPickcodes - pickcode,
                            message = "无法提取番号，已跳过：${item.name}"
                        )
                    }
                    return@launch
                }
                if (pickcode in _uiState.value.addedPickcodes) {
                    _uiState.update {
                        it.copy(
                            addingPickcodes = it.addingPickcodes - pickcode,
                            message = "这个视频已经添加或正在添加"
                        )
                    }
                    return@launch
                }
                val conflict = recordRepository.findStandardSameNumberCandidate(item.name, pickcode)
                if (conflict != null) {
                    _uiState.update {
                        it.copy(
                            addingPickcodes = it.addingPickcodes - pickcode,
                            pendingReplaceConflict = PendingReplaceConflict(
                                item = item,
                                oldPickcode = conflict.pickcode,
                                oldFileName = conflict.fileName,
                                movieNumber = conflict.movieNumber.orEmpty()
                            )
                        )
                    }
                    return@launch
                }
                enqueueAddVideo(item, forceDistinct = false, alreadyMarkedAdding = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        message = error.message ?: "添加前检查失败"
                    )
                }
            }
        }
    }

    fun dismissReplaceConflict() {
        _uiState.update { it.copy(pendingReplaceConflict = null) }
    }

    fun confirmReplacePickcode() {
        val conflict = _uiState.value.pendingReplaceConflict ?: return
        _uiState.update { it.copy(pendingReplaceConflict = null) }
        viewModelScope.launch {
            val newPickcode = conflict.item.pickcode ?: return@launch
            runCatching {
                recordRepository.replacePickcode(
                    oldPickcode = conflict.oldPickcode,
                    newPickcode = newPickcode,
                    newVideoName = conflict.item.name
                )
                scrapeRepository.appendLog("已替换同番号影片 pickcode：${conflict.movieNumber} ${conflict.oldPickcode} -> $newPickcode")
                CloudAddResult(pickcode = newPickcode, message = "已替换 ${conflict.movieNumber} 的播放源")
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addedPickcodes = (it.addedPickcodes - conflict.oldPickcode) + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "替换播放源失败") }
            }
        }
    }

    fun addConflictAsNewMovie() {
        val conflict = _uiState.value.pendingReplaceConflict ?: return
        _uiState.update { it.copy(pendingReplaceConflict = null) }
        enqueueAddVideo(conflict.item, forceDistinct = true)
    }

    private fun enqueueAddVideo(item: Cloud115FileItem, forceDistinct: Boolean, alreadyMarkedAdding: Boolean = false) {
        val pickcode = item.pickcode
        if (pickcode.isNullOrBlank()) {
            _uiState.update { it.copy(message = "这个视频没有 pickcode，无法添加") }
            return
        }
        if (pickcode in _uiState.value.addedPickcodes || (!alreadyMarkedAdding && pickcode in _uiState.value.addingPickcodes)) {
            _uiState.update { it.copy(message = "这个视频已经在添加队列中") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                val nextAddingPickcodes = if (alreadyMarkedAdding) {
                    it.addingPickcodes
                } else {
                    it.addingPickcodes + pickcode
                }
                it.copy(
                    addingPickcodes = nextAddingPickcodes,
                    message = progressMessage("正在添加 ${item.name}")
                )
            }
            scrapeRepository.appendLog("网盘添加已加入队列：${item.name} / $pickcode")
            runCatching {
                withContext(Dispatchers.IO) {
                    withAddLock(item.name) {
                        processCloudVideoAdd(item, pickcode, forceDistinct)
                    }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        addedPickcodes = it.addedPickcodes + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                scrapeRepository.appendLog("网盘添加失败：${item.name} / $pickcode，原因：$message")
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        message = message
                    )
                }
            }
        }
    }

    private suspend fun collectFolderVideoCandidates(
        folder: Cloud115FileItem,
        path: String,
        excludedVideoNames: Set<String>,
        skipBelowSizeBytes: Long,
        candidates: MutableList<Cloud115FileItem>,
        summary: CloudFolderBatchSummary,
        numberChecker: CloudAddNumberChecker,
        delayBeforeRequest: Boolean
    ) {
        val cid = folder.cid ?: run {
            summary.failedFolders += 1
            summary.rememberFailure("$path: 文件夹缺少 CID")
            return
        }
        if (delayBeforeRequest) {
            delay(Random.nextLong(CHILD_FOLDER_LIST_DELAY_MIN_MS, CHILD_FOLDER_LIST_DELAY_MAX_MS + 1))
        }
        val children = runCatching {
            strmRepository.listFiles(cid)
        }.getOrElse { error ->
            if (!delayBeforeRequest) throw error
            val message = error.message ?: error::class.java.simpleName
            summary.failedFolders += 1
            summary.rememberFailure("$path: $message")
            scrapeRepository.appendLog("读取子文件夹失败：$path / $cid，原因：$message")
            return
        }
        children
            .asSequence()
            .filter { !it.isDirectory && it.isVideoFile() }
            .sortedBy { it.name.lowercase() }
            .forEach { item ->
                val reason = item.batchSkipReason(excludedVideoNames, skipBelowSizeBytes)
                    ?: if (numberChecker.extract(item.name) == null) "无法提取番号" else null
                if (reason == null) {
                    candidates += item
                } else {
                    summary.skippedVideos += 1
                    scrapeRepository.appendLog("批量添加跳过：$path/${item.name}，原因：$reason")
                }
            }
        children
            .asSequence()
            .filter { it.isDirectory && it.cid != null }
            .sortedBy { it.name.lowercase() }
            .forEach { child ->
                collectFolderVideoCandidates(
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

    private suspend fun processCloudVideoAdd(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): CloudAddResult {
        if (recordRepository.get(pickcode) != null) {
            scrapeRepository.appendLog("网盘添加跳过：${item.name} / $pickcode，原因：影片已经添加")
            return CloudAddResult(pickcode = pickcode, message = "影片已经添加")
        }
        scrapeRepository.appendLog("开始处理网盘添加队列：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独入库：${generated.fileName}")
            return CloudAddResult(
                pickcode = pickcode,
                message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
            )
        }

        scrapeRepository.appendLog("STRM 写入完成，开始扫描单个 STRM：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = !generated.forceDistinct)

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
            ?: error("STRM 已添加，但无法从文件名提取番号，无法自动刮削")
        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(pickcode, movieForScrape.id)

        val source = settingsRepository.getDefaultScrapeSource()
        scrapeRepository.appendLog("开始刮削队列影片：$number，来源：$source")
        val scrapeResult = scrapeRepository.scrapeStrmUriWithOutput(
            libraryRootUri = libraryRoot,
            strmUri = generated.strmUri,
            source = source,
            forceDistinct = generated.forceDistinct
        )
        scrapeRepository.appendLog("刮削完成，开始刷新单个影片：$number")
        val refreshedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(scrapeResult.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        if (refreshedMovie != null) {
            if (
                addedMovie != null &&
                addedMovie.id != refreshedMovie.id &&
                addedMovie.videoUri != refreshedMovie.videoUri
            ) {
                movieRepository.deleteMovie(addedMovie.id)
                scrapeRepository.appendLog("已删除刮削前临时入库记录，避免重复显示：${addedMovie.videoName}")
            }
            recordRepository.updateStrmLocation(
                pickcode = pickcode,
                strmUri = refreshedMovie.videoUri,
                libraryRootUri = refreshedMovie.libraryRootUri,
                movieId = refreshedMovie.id
            )
        } else {
            scrapeRepository.appendLog("未定位到整理后的 STRM，跳过单片刷新：$number")
        }
        return CloudAddResult(
            pickcode = pickcode,
            message = if (generated.created) {
                "已添加并刮削：$number"
            } else {
                "STRM 已存在，已刷新并刮削：$number"
            }
        )
    }

    private suspend fun processCloudVideoAddAllowScrapeFailure(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): CloudAddResult {
        scrapeRepository.appendLog("开始处理网盘文件夹批量添加：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独刮削：${generated.fileName}")
            return CloudAddResult(
                pickcode = pickcode,
                message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
            )
        }

        scrapeRepository.appendLog("批量添加 STRM 写入完成，开始扫描：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        addedMovie?.let { recordRepository.attachMovie(pickcode, it.id) }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            scrapeRepository.appendLog("批量添加未刮削：${item.name}，原因：$reason")
            return CloudAddResult(
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
        scrapeRepository.appendLog("开始批量刮削影片：$number，来源：$source")
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
            scrapeRepository.appendLog("批量添加已入库但刮削失败：$number，原因：$reason")
            return CloudAddResult(
                pickcode = pickcode,
                message = "已添加但刮削失败：$number",
                scrapeFailed = true
            )
        }

        scrapeRepository.appendLog("批量刮削完成，开始刷新单个影片：$number")
        val refreshedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(scrapeResult.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        if (refreshedMovie != null) {
            if (
                addedMovie != null &&
                addedMovie.id != refreshedMovie.id &&
                addedMovie.videoUri != refreshedMovie.videoUri
            ) {
                movieRepository.deleteMovie(addedMovie.id)
                scrapeRepository.appendLog("已删除批量刮削前临时入库记录，避免重复显示：${addedMovie.videoName}")
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
            scrapeRepository.appendLog("批量刮削后未定位到整理后的 STRM，已清空原影片失败标记：$number")
        }
        return CloudAddResult(
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
            scrapeRepository.appendLog("同番号添加任务等待整理完成：$key")
        }
        return lock.withLock {
            block()
        }
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

    private fun loadCurrent() {
        val current = backStack.last()
        val requestedPath = backStack.toList()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    path = requestedPath,
                    isLoading = it.items.isEmpty() || it.path != requestedPath,
                    errorMessage = null
                )
            }
            runCatching {
                val sortOption = _uiState.value.sortOption
                val sortAscending = _uiState.value.sortAscending
                val items = strmRepository.listFiles(current.cid)
                coroutineScope {
                    val addedPickcodes = async { strmRepository.existingPickcodesForVisibleItems(items) }
                    val addedDomesticFolderCids = async { domesticMovieRepository.addedFolderCidsForVisibleItems(items) }
                    CloudDirectoryLoadResult(
                        items = withContext(Dispatchers.Default) { items.sortedByCloudOption(sortOption, sortAscending) },
                        addedPickcodes = addedPickcodes.await(),
                        addedDomesticFolderCids = addedDomesticFolderCids.await()
                    )
                }
            }.onSuccess { result ->
                if (backStack != requestedPath) return@onSuccess
                _uiState.update {
                    it.copy(
                        items = result.items,
                        addedPickcodes = result.addedPickcodes,
                        addedDomesticFolderCids = result.addedDomesticFolderCids,
                        excludedVideoNames = settingsRepository.getCloudExcludedVideoNames(),
                        path = backStack.toList(),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (backStack != requestedPath) return@onFailure
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "读取 115 目录失败"
                    )
                }
            }
        }
    }

    companion object {
        private const val CHILD_FOLDER_LIST_DELAY_MIN_MS = 1_500L
        private const val CHILD_FOLDER_LIST_DELAY_MAX_MS = 3_000L
        private const val ROOT_CID = 0L
        private const val ROOT_NAME = "根目录"

        fun factory(
            strmRepository: Cloud115StrmRepository,
            recordRepository: CloudStrmRecordRepository,
            settingsRepository: AppSettingsRepository,
            movieRepository: MovieRepository,
            scrapeRepository: StrmScrapeRepository,
            domesticMovieRepository: DomesticMovieRepository,
            folderBatchTaskRepository: CloudFolderBatchTaskRepository,
            folderBatchTaskRunner: CloudFolderBatchTaskRunner
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CloudBrowserViewModel(
                        strmRepository,
                        recordRepository,
                        settingsRepository,
                        movieRepository,
                        scrapeRepository,
                        domesticMovieRepository,
                        folderBatchTaskRepository,
                        folderBatchTaskRunner
                    ) as T
            }
    }
}

enum class CloudSortOption {
    ModifiedTime,
    Size
}

private fun List<Cloud115FileItem>.sortedByCloudOption(
    option: CloudSortOption,
    ascending: Boolean
): List<Cloud115FileItem> {
    val comparator = when (option) {
        CloudSortOption.ModifiedTime -> if (ascending) {
            compareBy<Cloud115FileItem> { it.modifiedAt ?: Long.MAX_VALUE }
        } else {
            compareByDescending { it.modifiedAt ?: Long.MIN_VALUE }
        }
        CloudSortOption.Size -> if (ascending) {
            compareBy { it.size ?: Long.MAX_VALUE }
        } else {
            compareByDescending { it.size ?: Long.MIN_VALUE }
        }
    }
    return sortedWith(comparator.thenBy { it.name.lowercase() })
}

data class CloudBrowserUiState(
    val items: List<Cloud115FileItem> = emptyList(),
    val path: List<CloudPathItem> = emptyList(),
    val isLoading: Boolean = false,
    val sortOption: CloudSortOption = CloudSortOption.ModifiedTime,
    val sortAscending: Boolean = false,
    val addingPickcodes: Set<String> = emptySet(),
    val addedPickcodes: Set<String> = emptySet(),
    val excludedVideoNames: Set<String> = emptySet(),
    val addingDomesticFolderCids: Set<Long> = emptySet(),
    val addedDomesticFolderCids: Set<Long> = emptySet(),
    val pendingReplaceConflict: PendingReplaceConflict? = null,
    val openMovieId: Long? = null,
    val scrollResetVersion: Int = 0,
    val errorMessage: String? = null,
    val message: String? = null
)

data class CloudPathItem(
    val cid: Long,
    val name: String
)

data class CloudScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

private data class CloudAddResult(
    val pickcode: String,
    val message: String,
    val scrapeFailed: Boolean = false
)

private data class CloudDirectoryLoadResult(
    val items: List<Cloud115FileItem>,
    val addedPickcodes: Set<String>,
    val addedDomesticFolderCids: Set<Long>
)

private data class PendingFolderBatchAdd(
    val folder: Cloud115FileItem,
    val cid: Long
)

data class PendingReplaceConflict(
    val item: Cloud115FileItem,
    val oldPickcode: String,
    val oldFileName: String,
    val movieNumber: String
)

private data class CloudFolderBatchSummary(
    val folderName: String,
    var queuedVideos: Int = 0,
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

    fun toUserMessage(): String {
        if (queuedVideos == 0 && addedVideos == 0) {
            return "文件夹没有可添加的视频，已跳过 $skippedVideos 个"
        }
        return buildString {
            append("文件夹添加完成：入库 $addedVideos 个")
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

