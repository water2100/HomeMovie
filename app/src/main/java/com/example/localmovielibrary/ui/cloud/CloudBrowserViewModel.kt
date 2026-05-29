package com.example.localmovielibrary.ui.cloud

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.Cloud115StrmRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.MissavCookieRequiredException
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.ui.shared.HiddenMissavWebRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudBrowserViewModel(
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val domesticMovieRepository: DomesticMovieRepository
) : ViewModel() {
    private val backStack = mutableListOf(CloudPathItem(0L, "根目录"))
    private val addQueueMutex = Mutex()
    private val _uiState = MutableStateFlow(CloudBrowserUiState(path = backStack.toList(), isLoading = true))
    val uiState: StateFlow<CloudBrowserUiState> = _uiState

    init {
        loadCurrent()
        warmUpAddedPickcodeIndex()
    }

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

    fun addDomesticFolder(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        if (cid in _uiState.value.addingDomesticFolderCids || cid in _uiState.value.addedDomesticFolderCids) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    addingDomesticFolderCids = it.addingDomesticFolderCids + cid,
                    message = "正在添加国产目录：${item.name}"
                )
            }
            runCatching { domesticMovieRepository.addFolder(item) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            addingDomesticFolderCids = state.addingDomesticFolderCids - cid,
                            addedDomesticFolderCids = state.addedDomesticFolderCids + cid,
                            message = "已添加到国产：${item.name}"
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
        _uiState.update {
            val nextAscending = !it.sortAscending
            it.copy(
                sortAscending = nextAscending,
                items = it.items.sortedByModifiedTime(nextAscending)
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeOpenMovie() {
        _uiState.update { it.copy(openMovieId = null) }
    }

    fun onHiddenMissavHtmlReady(requestId: Long, html: String, cookie: String) {
        val pending = _uiState.value.pendingMissavScrape ?: return
        val request = _uiState.value.hiddenMissavRequest ?: return
        if (request.id != requestId) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hiddenMissavRequest = null,
                    message = "已通过 WebView 获取 MissAV 页面，正在写入元数据..."
                )
            }
            runCatching {
                settingsRepository.saveMissavCookies(cookie)
                val movie = movieRepository.findMovieByNumberAndVariant(pending.libraryRootUri, pending.number, pending.sourceName)
                    ?: error("没有找到刚添加的影片：${pending.number}")
                scrapeRepository.scrapeMovieWithMissavHtml(movie, html, cookie)
                movieRepository.scanLibrary(Uri.parse(pending.libraryRootUri))
                CloudAddResult(
                    pickcode = pending.pickcode,
                    message = "已添加并刮削：${pending.number}"
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        pendingMissavScrape = null,
                        addedPickcodes = it.addedPickcodes + result.pickcode,
                        message = result.message
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pending.pickcode,
                        pendingMissavScrape = null,
                        message = error.message ?: "MissAV WebView 刮削失败"
                    )
                }
            }
        }
    }

    fun onHiddenMissavFailed(requestId: Long, message: String) {
        val request = _uiState.value.hiddenMissavRequest ?: return
        val pending = _uiState.value.pendingMissavScrape
        if (request.id != requestId) return
        scrapeRepository.appendLog("网盘添加影片时 MissAV 隐藏 WebView 抓取失败：$message")
        _uiState.update {
            it.copy(
                addingPickcodes = pending?.let { pendingItem -> it.addingPickcodes - pendingItem.pickcode } ?: it.addingPickcodes,
                hiddenMissavRequest = null,
                pendingMissavScrape = null,
                message = message
            )
        }
    }

    fun generateStrm(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(generatingCid = cid, message = null) }
            runCatching { strmRepository.generateStrmForFolder(cid) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            generatingCid = null,
                            message = "已生成 ${result.created} 个 STRM，跳过 ${result.skipped} 个"
                        )
                    }
                    loadCurrent()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            generatingCid = null,
                            message = error.message ?: "生成 STRM 失败"
                        )
                    }
                }
        }
    }

    fun addVideoToLibrary(item: Cloud115FileItem) {
        addVideoToLibraryWithConflictCheck(item)
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
                message = "\u6b63\u5728\u68c0\u67e5 ${item.name}"
            )
        }
        viewModelScope.launch {
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
                        message = result.message
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
                it.copy(
                    addingPickcodes = it.addingPickcodes + pickcode,
                    message = "正在添加 ${item.name}"
                )
            }
            scrapeRepository.appendLog("网盘添加已加入队列：${item.name} / $pickcode")
            runCatching {
                withContext(Dispatchers.IO) {
                    addQueueMutex.withLock {
                    scrapeRepository.appendLog("开始处理网盘添加队列：${item.name} / $pickcode")
                    val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
                    val libraryRoot = settingsRepository.getLibraryRootUri()
                        ?: error("请先到设置页选择影片库目录")
                    val rootUri = Uri.parse(libraryRoot)

                    if (!generated.shouldScrape) {
                        scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独入库：${generated.fileName}")
                        return@withLock CloudAddResult(
                            pickcode = pickcode,
                            message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
                        )
                    }

                    scrapeRepository.appendLog("STRM 写入完成，开始扫描单个 STRM：${generated.fileName}")
                    val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = !generated.forceDistinct)

                    val number = MovieNumberExtractor.extract(generated.fileName)
                        ?: MovieNumberExtractor.extract(item.name)
                        ?: error("STRM 已添加，但无法从文件名提取番号，无法自动刮削")
                    val movieForScrape = addedMovie
                        ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
                        ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
                    recordRepository.attachMovie(pickcode, movieForScrape.id)

                    val source = settingsRepository.getDefaultScrapeSource()
                    scrapeRepository.appendLog("开始刮削队列影片：$number，来源：$source")
                    val scrapeResult = scrapeRepository.scrapeMovieWithOutput(movieForScrape, source, forceDistinct = generated.forceDistinct)
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
                    CloudAddResult(
                        pickcode = pickcode,
                        message = if (generated.created) {
                            "已添加并刮削：$number"
                        } else {
                            "STRM 已存在，已刷新并刮削：$number"
                        }
                    )
                    }
                }
            }.onSuccess { result ->
                val addedPickcodes = runCatching { strmRepository.existingPickcodes() }.getOrDefault(_uiState.value.addedPickcodes + result.pickcode)
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        addedPickcodes = addedPickcodes,
                        message = result.message
                    )
                }
            }.onFailure { error ->
                val pending = buildPendingMissavContext(item, pickcode)
                if (
                    settingsRepository.getDefaultScrapeSource() == ScrapeSource.Missav &&
                    error is MissavCookieRequiredException &&
                    pending != null
                ) {
                    scrapeRepository.appendLog("网盘添加影片时 MissAV 返回 Cloudflare/403，切换到隐藏 WebView：${pending.number}")
                    _uiState.update {
                        it.copy(
                            hiddenMissavRequest = HiddenMissavWebRequest(
                                id = System.currentTimeMillis(),
                                number = pending.number,
                                url = "https://missav.ai/cn/${pending.number.lowercase()}"
                            ),
                            pendingMissavScrape = pending,
                            message = "MissAV 返回验证页，正在使用隐藏 WebView 获取页面"
                        )
                    }
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        message = error.message ?: "添加影片失败"
                    )
                }
            }
        }
    }

    private suspend fun buildPendingMissavContext(item: Cloud115FileItem, pickcode: String): PendingMissavScrape? {
        val libraryRoot = settingsRepository.getLibraryRootUri() ?: return null
        val number = MovieNumberExtractor.extract(item.name) ?: return null
        return PendingMissavScrape(
            libraryRootUri = libraryRoot,
            number = number,
            sourceName = item.name,
            pickcode = pickcode
        )
    }

    private fun loadCurrent() {
        val current = backStack.last()
        viewModelScope.launch {
            _uiState.update { it.copy(path = backStack.toList(), isLoading = true, errorMessage = null) }
            runCatching {
                val items = strmRepository.listFiles(current.cid)
                val addedPickcodes = strmRepository.existingPickcodesForVisibleItems(items)
                val addedDomesticFolderCids = domesticMovieRepository.addedFolderCids()
                Triple(items, addedPickcodes, addedDomesticFolderCids)
            }.onSuccess { (items, addedPickcodes, addedDomesticFolderCids) ->
                _uiState.update {
                    it.copy(
                        items = items.sortedByModifiedTime(it.sortAscending),
                        addedPickcodes = addedPickcodes,
                        addedDomesticFolderCids = addedDomesticFolderCids,
                        path = backStack.toList(),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "读取 115 目录失败"
                    )
                }
            }
        }
    }

    private fun warmUpAddedPickcodeIndex() {
        viewModelScope.launch {
            runCatching { recordRepository.existingPickcodes() }
                .onSuccess { addedPickcodes ->
                    _uiState.update {
                        it.copy(addedPickcodes = it.addedPickcodes + addedPickcodes)
                    }
                }
                .onFailure { error ->
                    scrapeRepository.appendLog("鍚庡彴琛ョ储寮曞凡鍏ュ簱 STRM 澶辫触锛?{error.message}")
                }
        }
    }

    companion object {
        fun factory(
            strmRepository: Cloud115StrmRepository,
            recordRepository: CloudStrmRecordRepository,
            settingsRepository: AppSettingsRepository,
            movieRepository: MovieRepository,
            scrapeRepository: StrmScrapeRepository,
            domesticMovieRepository: DomesticMovieRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CloudBrowserViewModel(strmRepository, recordRepository, settingsRepository, movieRepository, scrapeRepository, domesticMovieRepository) as T
            }
    }
}

private fun List<Cloud115FileItem>.sortedByModifiedTime(ascending: Boolean): List<Cloud115FileItem> {
    return if (ascending) {
        sortedWith(compareBy<Cloud115FileItem> { it.modifiedAt ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
    } else {
        sortedWith(compareByDescending<Cloud115FileItem> { it.modifiedAt ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
    }
}

data class CloudBrowserUiState(
    val items: List<Cloud115FileItem> = emptyList(),
    val path: List<CloudPathItem> = emptyList(),
    val isLoading: Boolean = false,
    val generatingCid: Long? = null,
    val sortAscending: Boolean = false,
    val addingPickcodes: Set<String> = emptySet(),
    val addedPickcodes: Set<String> = emptySet(),
    val addingDomesticFolderCids: Set<Long> = emptySet(),
    val addedDomesticFolderCids: Set<Long> = emptySet(),
    val hiddenMissavRequest: HiddenMissavWebRequest? = null,
    val pendingMissavScrape: PendingMissavScrape? = null,
    val pendingReplaceConflict: PendingReplaceConflict? = null,
    val openMovieId: Long? = null,
    val errorMessage: String? = null,
    val message: String? = null
)

data class CloudPathItem(
    val cid: Long,
    val name: String
)

private data class CloudAddResult(
    val pickcode: String,
    val message: String
)

data class PendingMissavScrape(
    val libraryRootUri: String,
    val number: String,
    val sourceName: String,
    val pickcode: String
)

data class PendingReplaceConflict(
    val item: Cloud115FileItem,
    val oldPickcode: String,
    val oldFileName: String,
    val movieNumber: String
)
