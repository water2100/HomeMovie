package com.example.localmovielibrary.ui.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.AddCustomTagResult
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MoviePlaybackPart
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.RemoveCustomTagResult
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.scraper.ScrapeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class DetailViewModel(
    context: Context,
    private val movieId: Long,
    private val repository: MovieRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val settingsRepository: AppSettingsRepository,
    private val cloud115Client: Cloud115Client
) : ViewModel() {
    private val appContext = context.applicationContext
    private val scrapeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val movie: StateFlow<MovieEntity?> = repository.observeMovie(movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping

    private val _similarMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    val similarMovies: StateFlow<List<MovieEntity>> = _similarMovies

    private val _playbackParts = MutableStateFlow<List<MoviePlaybackPart>>(emptyList())
    val playbackParts: StateFlow<List<MoviePlaybackPart>> = _playbackParts

    private val _cloudVideoSizeBytes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cloudVideoSizeBytes: StateFlow<Map<String, Long>> = _cloudVideoSizeBytes

    private val _cloudDeleteEnabled = MutableStateFlow(settingsRepository.isCloudDeleteEnabled())
    val cloudDeleteEnabled: StateFlow<Boolean> = _cloudDeleteEnabled

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DetailEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            movie
                .map { current -> current?.toDetailDerivedDataKey() }
                .distinctUntilChanged()
                .collectLatest {
                    val current = movie.value
                    if (current == null) {
                        _playbackParts.value = emptyList()
                        _similarMovies.value = emptyList()
                        return@collectLatest
                    }
                    val (parts, similar) = coroutineScope {
                        val partsDeferred = async { repository.getPlaybackParts(current.id) }
                        val similarDeferred = async { repository.findSimilarMovies(current) }
                        partsDeferred.await() to similarDeferred.await()
                    }
                    _playbackParts.value = parts
                    _cloudVideoSizeBytes.value = loadCloudVideoSizeBytes(parts)
                    _similarMovies.value = similar
            }
        }
    }

    private suspend fun loadCloudVideoSizeBytes(parts: List<MoviePlaybackPart>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val cachedRecords = cloudStrmRecordRepository.getByMovieId(movieId)
                .associateBy { it.strmUri }
            buildMap {
                parts.forEach { part ->
                    if (!part.videoUri.endsWith(".strm", ignoreCase = true)) return@forEach
                    cachedRecords[part.videoUri]?.videoSizeBytes
                        ?.takeIf { it > 0L }
                        ?.let { cachedSize ->
                            put(part.videoUri, cachedSize)
                            return@forEach
                        }
                    val content = runCatching {
                        appContext.contentResolver.openInputStream(Uri.parse(part.videoUri))
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                    }.getOrDefault("")
                    val pickcode = PickcodeExtractor.extract(content) ?: return@forEach
                    val sourceName = runCatching {
                        val sourceUri = Uri.parse(content)
                        val routeIndex = sourceUri.pathSegments.indexOfFirst {
                            it == "download_m3u" || it == "play" || it == "video_proxy"
                        }
                        sourceUri.pathSegments.getOrNull(routeIndex + 2)?.let(Uri::decode)
                    }.getOrNull().orEmpty()
                    val query = sourceName.ifBlank { part.fileName }
                    val size = runCatching {
                        cloud115Client.searchFiles(query, limit = 100)
                            .firstOrNull { it.pickcode == pickcode }
                            ?.size
                    }.getOrNull()
                    if (size != null && size > 0L) {
                        put(part.videoUri, size)
                        cloudStrmRecordRepository.saveVideoSize(pickcode, size)
                    }
                }
            }
        }

    fun toggleFavorite() {
        val current = movie.value ?: return
        val next = !current.isFavorite
        viewModelScope.launch {
            repository.setFavorite(current.id, next)
            events.send(DetailEvent.Message(if (next) "已加入收藏" else "已取消收藏"))
        }
    }

    fun toggleWatched() {
        val current = movie.value ?: return
        val next = !current.isWatched
        viewModelScope.launch {
            repository.setWatched(current.id, next)
            events.send(DetailEvent.Message(if (next) "已标记为已观看" else "已标记为未观看"))
        }
    }

    fun addCustomTag(tag: String) {
        val current = movie.value ?: return
        viewModelScope.launch {
            when (val result = repository.addCustomTag(current.id, tag)) {
                is AddCustomTagResult.Added -> events.send(DetailEvent.Message("已添加标签：${result.tag}"))
                AddCustomTagResult.AlreadyExists -> events.send(DetailEvent.Message("这个标签已经存在"))
                AddCustomTagResult.Blank -> events.send(DetailEvent.Message("请输入标签"))
                AddCustomTagResult.MovieNotFound -> events.send(DetailEvent.Message("影片记录不存在"))
            }
        }
    }

    fun removeCustomTag(tag: String) {
        val current = movie.value ?: return
        viewModelScope.launch {
            when (val result = repository.removeCustomTag(current.id, tag)) {
                is RemoveCustomTagResult.Removed -> events.send(DetailEvent.Message("已删除标签：${result.tag}"))
                RemoveCustomTagResult.NotFound -> events.send(DetailEvent.Message("这个标签不存在或来自类型"))
                RemoveCustomTagResult.Blank -> events.send(DetailEvent.Message("标签为空"))
                RemoveCustomTagResult.MovieNotFound -> events.send(DetailEvent.Message("影片记录不存在"))
            }
        }
    }

    fun refreshMovie() {
        val current = movie.value ?: return
        viewModelScope.launch {
            events.send(DetailEvent.Message("正在刷新影片..."))
            val refreshed = repository.refreshMovieRecoveringMovedStrm(current.id)
            if (refreshed != null) {
                events.send(DetailEvent.Message("影片已刷新"))
                if (refreshed.id != current.id) {
                    events.send(DetailEvent.OpenMovie(refreshed.id))
                }
            } else {
                events.send(DetailEvent.Message("无法刷新此影片，可能需要重新扫描影片库"))
            }
        }
    }

    fun deleteMovie(deleteCloud: Boolean = false) {
        val current = movie.value ?: return
        viewModelScope.launch {
            events.send(DetailEvent.Message(if (deleteCloud) "正在删除网盘文件和本地记录..." else "正在删除影片..."))
            runCatching {
                if (deleteCloud) {
                    deleteCloudFiles(current)
                }
                val result = repository.deleteMovieWithFiles(current.id)
                cloudStrmRecordRepository.deleteForMovie(current.id, result.pickcodes)
            }.onSuccess {
                events.send(DetailEvent.Deleted)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                events.send(DetailEvent.Message(error.message ?: "删除失败"))
            }
        }
    }

    private suspend fun deleteCloudFiles(current: MovieEntity) {
        val targets = linkedMapOf<String, String?>()
        val currentStrm = current.videoUri.readTextFromUri(appContext)
        PickcodeExtractor.extract(currentStrm)?.let { pickcode ->
            targets[pickcode] = currentStrm.extractCloudOriginalFileName() ?: current.videoName
        }
        cloudStrmRecordRepository.getByMovieId(current.id).forEach { record ->
            val content = record.strmUri.readTextFromUri(appContext)
            val nameHint = content.extractCloudOriginalFileName() ?: record.fileName
            targets[record.pickcode] = nameHint
        }
        if (targets.isEmpty()) error("没有找到可删除的 115 pickcode")
        targets.forEach { (pickcode, nameHint) ->
            cloud115Client.deleteFileByPickcode(pickcode, nameHint)
        }
    }

    fun renameMovieFile(newFileName: String) {
        val current = movie.value ?: return
        if (_isScraping.value) return
        viewModelScope.launch {
            _isScraping.value = true
            events.send(DetailEvent.Message("正在重命名 STRM..."))
            runCatching {
                repository.renameMovieStrmFile(current.id, newFileName)
            }.onSuccess { result ->
                _isScraping.value = false
                val message = if (result.oldFileName == result.newFileName) {
                    "文件名没有变化"
                } else {
                    "已重命名为 ${result.newFileName}"
                }
                events.send(DetailEvent.Message(message))
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                events.send(DetailEvent.Message(error.message ?: "重命名失败"))
            }
        }
    }

    fun menuFeedback(label: String) {
        viewModelScope.launch {
            events.send(DetailEvent.Message(label))
        }
    }

    fun scrapeWithDmm() {
        scrapeCurrent(ScrapeSource.Dmm)
    }

    fun scrapeWithDefault() {
        scrapeCurrent(scrapeRepository.getDefaultScrapeSource())
    }

    fun scrapeWithDmm2() {
        scrapeCurrent(ScrapeSource.Dmm2)
    }

    fun scrapeWithOfficial() {
        scrapeCurrent(ScrapeSource.Official)
    }

    fun scrapeWithJavbus() {
        scrapeCurrent(ScrapeSource.Javbus)
    }

    fun scrapeWithJavdb() {
        scrapeCurrent(ScrapeSource.TheJavDB)
    }

    fun scrapeWithCustomJson() {
        scrapeCurrent(ScrapeSource.CustomJson)
    }

    fun rescrapeWithDefault() {
        rescrapeCurrent(scrapeRepository.getDefaultScrapeSource())
    }

    fun rescrapeWithDmm() {
        rescrapeCurrent(ScrapeSource.Dmm)
    }

    fun rescrapeWithDmm2() {
        rescrapeCurrent(ScrapeSource.Dmm2)
    }

    fun rescrapeWithOfficial() {
        rescrapeCurrent(ScrapeSource.Official)
    }

    fun rescrapeWithJavbus() {
        rescrapeCurrent(ScrapeSource.Javbus)
    }

    fun rescrapeWithJavdb() {
        rescrapeCurrent(ScrapeSource.TheJavDB)
    }

    fun rescrapeWithCustomJson() {
        rescrapeCurrent(ScrapeSource.CustomJson)
    }

    fun clearScrapeFiles() {
        val current = movie.value ?: return
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("正在清除刮削内容并还原..."))
            runCatching {
                val result = scrapeRepository.clearScrapeFiles(current)
                scrapeRepository.appendLog("开始刷新单个影片，刷新还原后的 STRM")
                repository.scanSingleMovie(
                    rootUri = Uri.parse(current.libraryRootUri),
                    videoUri = Uri.parse(result.strmUri),
                    mergeByMovieNumber = true,
                    excludedMergeMovieId = current.id
                )?.id ?: repository.findMovieByNumber(current.libraryRootUri, result.number)?.id
            }.onSuccess { newMovieId ->
                if (newMovieId != null && newMovieId != current.id) {
                    repository.deleteMovie(current.id)
                }
                _isScraping.value = false
                events.trySend(DetailEvent.Message("已清除刮削内容并还原"))
                newMovieId?.let { events.trySend(DetailEvent.OpenMovie(it)) } ?: events.trySend(DetailEvent.Deleted)
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                scrapeRepository.appendLog("清除刮削内容失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "清除失败，请查看日志"))
            }
        }
    }

    private fun scrapeCurrent(source: ScrapeSource) {
        val current = movie.value ?: return
        if (_isScraping.value) return
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("开始${source.displayName}刮削..."))
            runCatching {
                val result = scrapeRepository.scrapeMovieWithOutput(current, source)
                scrapeRepository.appendLog("开始刷新单个影片，刷新整理后的文件")
                refreshScrapedMovie(current, result.info.number, result.strmUri)
            }.onSuccess {
                _isScraping.value = false
                events.trySend(DetailEvent.Message("刮削完成，文件已整理到影片文件夹"))
                it?.let { movieId -> events.trySend(DetailEvent.OpenMovie(movieId)) }
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                scrapeRepository.appendLog("当前影片刮削失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "刮削失败，请查看日志"))
            }
        }
    }

    private suspend fun refreshScrapedMovie(current: MovieEntity, number: String, knownStrmUri: String? = null): Long? {
        val finalUri = knownStrmUri ?: scrapeRepository.findStrmUriByNumber(
            current.libraryRootUri,
            number,
            partLabel = null,
            nameHint = current.videoName
        )
        val refreshed = finalUri?.let { strmUri ->
            repository.refreshMovieAfterScrape(
                original = current,
                scrapedStrmUri = strmUri,
                mergeByMovieNumber = true
            )
        }
        if (refreshed != null) {
            scrapeRepository.appendLog("单个影片刷新完成：${refreshed.videoName}")
            return refreshed.id
        }
        scrapeRepository.appendLog("未定位到整理后的 STRM，尝试刷新当前影片记录：$number")
        repository.refreshMovieRecoveringMovedStrm(current.id)?.let { return it.id }
        return repository.findMovieByNumber(current.libraryRootUri, number)?.id
    }

    private fun rescrapeCurrent(source: ScrapeSource) {
        val current = movie.value ?: return
        if (_isScraping.value) return
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("开始用 ${source.displayName} 重新刮削..."))
            runCatching {
                scrapeRepository.rescrapeMovie(current, source)
                repository.refreshMovieRecoveringMovedStrm(current.id)
            }.onSuccess {
                _isScraping.value = false
                events.trySend(DetailEvent.Message("重新刮削完成，影片信息已刷新"))
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                scrapeRepository.appendLog("当前影片重新刮削失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "重新刮削失败，请查看日志"))
            }
        }
    }

    companion object {
        fun factory(
            context: Context,
            movieId: Long,
            repository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            settingsRepository: AppSettingsRepository,
            cloud115Client: Cloud115Client
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DetailViewModel(context, movieId, repository, cloudStrmRecordRepository, scrapeRepository, settingsRepository, cloud115Client) as T
            }
    }
}

private fun String.readTextFromUri(context: Context): String =
    runCatching {
        context.contentResolver.openInputStream(Uri.parse(this))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
    }.getOrDefault("")

private fun String.extractCloudOriginalFileName(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val segments = uri.pathSegments
    val routeIndex = segments.indexOfFirst { it == "download_m3u" || it == "play" || it == "video_proxy" }
    val encodedName = routeIndex
        .takeIf { it >= 0 && it + 2 < segments.size }
        ?.let { segments[it + 2] }
        ?: return null
    return runCatching { URLDecoder.decode(encodedName, Charsets.UTF_8.name()) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private data class DetailDerivedDataKey(
    val id: Long,
    val libraryRootUri: String,
    val videoUri: String,
    val videoName: String,
    val title: String,
    val originalTitle: String?,
    val actors: List<String>
)

private fun MovieEntity.toDetailDerivedDataKey(): DetailDerivedDataKey =
    DetailDerivedDataKey(
        id = id,
        libraryRootUri = libraryRootUri,
        videoUri = videoUri,
        videoName = videoName,
        title = title,
        originalTitle = originalTitle,
        actors = actors
    )

private val ScrapeSource.displayName: String
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

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
    data class OpenMovie(val movieId: Long) : DetailEvent
    data object Deleted : DetailEvent
}
