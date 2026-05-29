package com.example.localmovielibrary.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MoviePlaybackPart
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.MissavCookieRequiredException
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.ScrapeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

class DetailViewModel(
    movieId: Long,
    private val repository: MovieRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {
    private val scrapeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val movie: StateFlow<MovieEntity?> = repository.observeMovie(movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping

    private val _hiddenMissavRequest = MutableStateFlow<HiddenMissavRequest?>(null)
    val hiddenMissavRequest: StateFlow<HiddenMissavRequest?> = _hiddenMissavRequest

    val similarMovies: StateFlow<List<MovieEntity>> = combine(
        repository.observeMovies(),
        movie
    ) { movies, current ->
        current?.let { findSimilarMovies(it, movies) } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playbackParts = MutableStateFlow<List<MoviePlaybackPart>>(emptyList())
    val playbackParts: StateFlow<List<MoviePlaybackPart>> = _playbackParts

    private val _thumbBackgroundSettings = MutableStateFlow(
        ThumbBackgroundSettings(
            enabled = settingsRepository.isDetailThumbBackgroundEnabled(),
            alphaPercent = settingsRepository.getDetailThumbBackgroundAlphaPercent()
        )
    )
    val thumbBackgroundSettings: StateFlow<ThumbBackgroundSettings> = _thumbBackgroundSettings

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DetailEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            movie.collect { current ->
                _playbackParts.value = current?.let { repository.getPlaybackParts(it.id) }.orEmpty()
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

    fun refreshMovie() {
        val current = movie.value ?: return
        viewModelScope.launch {
            events.send(DetailEvent.Message("正在刷新影片..."))
            val success = repository.refreshMovie(current.id)
            events.send(DetailEvent.Message(if (success) "影片已刷新" else "无法刷新此影片"))
        }
    }

    fun deleteMovie() {
        val current = movie.value ?: return
        viewModelScope.launch {
            val result = repository.deleteMovieWithFiles(current.id)
            cloudStrmRecordRepository.deleteForMovie(current.id, result.pickcodes)
            events.send(DetailEvent.Deleted)
        }
    }

    fun setThumbBackgroundEnabled(enabled: Boolean) {
        settingsRepository.saveDetailThumbBackgroundEnabled(enabled)
        _thumbBackgroundSettings.value = _thumbBackgroundSettings.value.copy(enabled = enabled)
    }

    fun setThumbBackgroundAlphaPercent(percent: Int) {
        val coerced = percent.coerceIn(0, 100)
        settingsRepository.saveDetailThumbBackgroundAlphaPercent(coerced)
        _thumbBackgroundSettings.value = _thumbBackgroundSettings.value.copy(alphaPercent = coerced)
    }

    fun menuFeedback(label: String) {
        viewModelScope.launch {
            events.send(DetailEvent.Message(label))
        }
    }

    fun scrapeWithDmm() {
        scrapeCurrent(ScrapeSource.Dmm, allowCookieRefresh = false)
    }

    fun scrapeWithDmm2() {
        scrapeCurrent(ScrapeSource.Dmm2, allowCookieRefresh = false)
    }

    fun scrapeWithOfficial() {
        scrapeCurrent(ScrapeSource.Official, allowCookieRefresh = false)
    }

    fun scrapeWithMissav() {
        scrapeCurrent(ScrapeSource.Missav, allowCookieRefresh = true)
    }

    fun rescrapeWithDefault() {
        rescrapeCurrent(scrapeRepository.getDefaultScrapeSource(), allowCookieRefresh = true)
    }

    fun rescrapeWithDmm() {
        rescrapeCurrent(ScrapeSource.Dmm, allowCookieRefresh = false)
    }

    fun rescrapeWithDmm2() {
        rescrapeCurrent(ScrapeSource.Dmm2, allowCookieRefresh = false)
    }

    fun rescrapeWithOfficial() {
        rescrapeCurrent(ScrapeSource.Official, allowCookieRefresh = false)
    }

    fun rescrapeWithMissav() {
        rescrapeCurrent(ScrapeSource.Missav, allowCookieRefresh = true)
    }

    fun retryMissavAfterCookieSaved() {
        scrapeCurrent(ScrapeSource.Missav, allowCookieRefresh = false)
    }

    fun onHiddenMissavHtmlReady(requestId: Long, html: String, cookie: String) {
        val request = _hiddenMissavRequest.value ?: return
        if (request.id != requestId) return
        val current = movie.value ?: return
        _hiddenMissavRequest.value = null
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("已通过 WebView 获取页面，正在解析并写入 NFO..."))
            runCatching {
                if (request.rescrape) {
                    scrapeRepository.rescrapeMovieWithMissavHtml(current, html, cookie)
                    repository.refreshMovie(current.id)
                    null
                } else {
                    val info = scrapeRepository.scrapeMovieWithMissavHtml(current, html, cookie)
                    scrapeRepository.appendLog("开始刷新单个影片，刷新 MissAV WebView 刮削结果")
                    refreshScrapedMovie(current, info.number)
                }
            }.onSuccess {
                _isScraping.value = false
                events.trySend(DetailEvent.Message(if (request.rescrape) "MissAV 重新刮削完成" else "MissAV 刮削完成"))
                it?.let { movieId -> events.trySend(DetailEvent.OpenMovie(movieId)) }
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                scrapeRepository.appendLog("MissAV WebView 刮削失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "MissAV WebView 刮削失败，请查看日志"))
            }
        }
    }

    fun onHiddenMissavFailed(requestId: Long, message: String) {
        val request = _hiddenMissavRequest.value ?: return
        if (request.id != requestId) return
        _hiddenMissavRequest.value = null
        _isScraping.value = false
        scrapeRepository.appendLog("MissAV 隐藏 WebView 抓取失败：$message")
        events.trySend(DetailEvent.Message(message))
    }

    fun clearScrapeFiles() {
        val current = movie.value ?: return
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("正在清除刮削内容并还原..."))
            runCatching {
                val number = scrapeRepository.clearScrapeFiles(current)
                scrapeRepository.appendLog("开始重新扫描影片库，刷新还原后的 STRM")
                val count = repository.scanLibrary(Uri.parse(current.libraryRootUri))
                scrapeRepository.appendLog("影片库重新扫描完成：$count 部影片")
                repository.findMovieByNumber(current.libraryRootUri, number)?.id
            }.onSuccess { newMovieId ->
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

    private fun scrapeCurrent(source: ScrapeSource, allowCookieRefresh: Boolean) {
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
                if (source == ScrapeSource.Missav && error is MissavCookieRequiredException) {
                    handleMissavCookieError(current, error, allowCookieRefresh, isRescrape = false)
                    return@onFailure
                }
                scrapeRepository.appendLog("当前影片刮削失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "刮削失败，请查看日志"))
            }
        }
    }

    private suspend fun refreshScrapedMovie(current: MovieEntity, number: String, knownStrmUri: String? = null): Long? {
        val rootUri = Uri.parse(current.libraryRootUri)
        val finalUri = knownStrmUri ?: scrapeRepository.findStrmUriByNumber(
            current.libraryRootUri,
            number,
            partLabel = null,
            nameHint = current.videoName
        )
        val refreshed = finalUri?.let {
            repository.scanSingleMovie(rootUri, Uri.parse(it), mergeByMovieNumber = true)
        }
        if (refreshed != null) {
            scrapeRepository.appendLog("单个影片刷新完成：${refreshed.videoName}")
            return refreshed.id
        }
        scrapeRepository.appendLog("未定位到整理后的 STRM，尝试刷新当前影片记录：$number")
        return if (repository.refreshMovie(current.id)) current.id else repository.findMovieByNumber(current.libraryRootUri, number)?.id
    }

    private fun rescrapeCurrent(source: ScrapeSource, allowCookieRefresh: Boolean) {
        val current = movie.value ?: return
        if (_isScraping.value) return
        scrapeScope.launch {
            _isScraping.value = true
            events.trySend(DetailEvent.Message("开始用 ${source.displayName} 重新刮削..."))
            runCatching {
                scrapeRepository.rescrapeMovie(current, source)
                repository.refreshMovie(current.id)
            }.onSuccess {
                _isScraping.value = false
                events.trySend(DetailEvent.Message("重新刮削完成，影片信息已刷新"))
            }.onFailure { error ->
                _isScraping.value = false
                if (error is CancellationException) throw error
                if (source == ScrapeSource.Missav && error is MissavCookieRequiredException) {
                    handleMissavCookieError(current, error, allowCookieRefresh, isRescrape = true)
                    return@onFailure
                }
                scrapeRepository.appendLog("当前影片重新刮削失败：${error.message ?: error::class.java.simpleName}")
                events.trySend(DetailEvent.Message(error.message ?: "重新刮削失败，请查看日志"))
            }
        }
    }

    private fun handleMissavCookieError(
        current: MovieEntity,
        error: MissavCookieRequiredException,
        allowCookieRefresh: Boolean,
        isRescrape: Boolean
    ) {
        val number = MovieNumberExtractor.extract(current.videoName)
            ?: MovieNumberExtractor.extract(current.title)
            ?: current.videoName.substringBeforeLast('.', current.videoName)
        if (!allowCookieRefresh) {
            val message = if (error.hasCookie) {
                "MissAV 后台请求仍然被 403 阻挡，可能是 OkHttp 指纹被识别；请查看日志"
            } else {
                "MissAV 没有获取到可用 Cookie，请到设置页获取 Cookie"
            }
            scrapeRepository.appendLog("$message。原始错误：${error.message ?: error::class.java.simpleName}")
            events.trySend(DetailEvent.Message(message))
            return
        }

        if (error.hasCookie) {
            scrapeRepository.appendLog("MissAV 后台请求被挡，切换到隐藏 WebView 抓取页面：${error.message ?: error::class.java.simpleName}")
            events.trySend(DetailEvent.Message("MissAV 后台请求被挡，正在用 WebView 抓取页面"))
        } else {
            scrapeRepository.appendLog("MissAV 尚未配置 Cookie，使用隐藏 WebView 获取页面和 Cookie")
            events.trySend(DetailEvent.Message("MissAV 需要 Cookie，正在用 WebView 获取页面"))
        }
        _isScraping.value = true
        _hiddenMissavRequest.value = HiddenMissavRequest(
            id = System.currentTimeMillis(),
            number = number,
            url = "https://missav.ai/cn/${number.lowercase()}",
            rescrape = isRescrape
        )
    }

    companion object {
        fun factory(
            movieId: Long,
            repository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            settingsRepository: AppSettingsRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DetailViewModel(movieId, repository, cloudStrmRecordRepository, scrapeRepository, settingsRepository) as T
            }
    }
}

data class ThumbBackgroundSettings(
    val enabled: Boolean = false,
    val alphaPercent: Int = 32
)

private val ScrapeSource.displayName: String
    get() = when (this) {
        ScrapeSource.Dmm -> "DMM"
        ScrapeSource.Dmm2 -> "DMM2"
        ScrapeSource.Official -> "Official"
        ScrapeSource.Missav -> "MissAV"
    }

private fun findSimilarMovies(current: MovieEntity, movies: List<MovieEntity>): List<MovieEntity> {
    val currentCode = current.codeInfo()
    val currentActors = current.actors.map { it.normalized() }.filter { it.isNotBlank() }.toSet()
    val candidates = movies.asSequence()
        .filter { it.id != current.id }
        .map { movie ->
            val movieCode = movie.codeInfo()
            val sameActorScore = movie.actors.count { it.normalized() in currentActors }
            val samePrefix = currentCode != null && movieCode?.prefix == currentCode.prefix
            val distance = if (currentCode != null && movieCode != null && samePrefix) {
                abs(movieCode.number - currentCode.number)
            } else {
                Int.MAX_VALUE
            }
            val score = sameActorScore * 1000 + if (samePrefix) 250 else 0
            movie to SimilarRank(score = score, distance = distance)
        }
        .filter { (_, rank) -> rank.score > 0 }
        .sortedWith(
            compareByDescending<Pair<MovieEntity, SimilarRank>> { it.second.score }
                .thenBy { it.second.distance }
                .thenBy { pair -> pair.first.sortTitle.ifBlank { pair.first.title }.lowercase() }
        )
        .map { it.first }
        .toList()

    return candidates.take(12)
}

private data class SimilarRank(val score: Int, val distance: Int)

private data class CodeInfo(val prefix: String, val number: Int)

private fun MovieEntity.codeInfo(): CodeInfo? {
    val source = listOf(title, originalTitle.orEmpty(), videoName).joinToString(" ")
    val match = Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})\b""").find(source) ?: return null
    return CodeInfo(
        prefix = match.groupValues[1].uppercase(),
        number = match.groupValues[2].toIntOrNull() ?: return null
    )
}

private fun String.normalized(): String = trim().lowercase()

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
    data class OpenMovie(val movieId: Long) : DetailEvent
    data class OpenMissavCookie(val number: String) : DetailEvent
    data object Deleted : DetailEvent
}

data class HiddenMissavRequest(
    val id: Long,
    val number: String,
    val url: String,
    val rescrape: Boolean = false
)
