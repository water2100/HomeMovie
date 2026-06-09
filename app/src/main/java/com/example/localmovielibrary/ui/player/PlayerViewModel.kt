package com.example.localmovielibrary.ui.player

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.localmovielibrary.asr.SherpaOnnxSubtitleRecognizer
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.DirectLinkRepository
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.PlaybackProgressRepository
import com.example.localmovielibrary.diagnostics.RuntimeErrorLog
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.playback.PlaybackRequest
import com.example.localmovielibrary.playback.PlaybackResolver
import com.example.localmovielibrary.playback.SubtitleRenderersFactory
import com.example.localmovielibrary.playback.vr.VrControlMode
import com.example.localmovielibrary.playback.vr.VrMode
import com.example.localmovielibrary.playback.vr.VrModeSettings
import com.example.localmovielibrary.subtitle.AvsubtitlesCloudflareException
import com.example.localmovielibrary.subtitle.AvsubtitlesSubtitleRepository
import com.example.localmovielibrary.subtitle.JavzimuSubtitleRepository
import com.example.localmovielibrary.subtitle.JavzimuCloudflareException
import com.example.localmovielibrary.subtitle.JavzimuSubtitleResult
import com.example.localmovielibrary.subtitle.LocalSubtitleFile
import com.example.localmovielibrary.subtitle.SavedSubtitleCue
import com.example.localmovielibrary.subtitle.LiveSubtitleStore
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.subtitle.XunleiSubtitleRepository
import com.example.localmovielibrary.subtitle.normalizeJavzimuSubtitleNumber
import com.example.localmovielibrary.subtitle.normalizeXunleiSubtitleNumber
import com.example.localmovielibrary.translate.TranslationClient
import com.example.localmovielibrary.util.normalizeMovieNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.abs

class PlayerViewModel(
    private val application: Application,
    private val videoUri: Uri,
    title: String,
    private val fileName: String,
    directLinkRepository: DirectLinkRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val playbackProgressRepository: PlaybackProgressRepository
) : AndroidViewModel(application) {
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)
    private var speedIndex = 1
    private val resolver = PlaybackResolver(application.contentResolver, directLinkRepository)
    private val translateClient = TranslationClient(settingsRepository)
    private val liveSubtitleStore = LiveSubtitleStore(application, settingsRepository)
    private val javzimuSubtitleRepository = JavzimuSubtitleRepository(application, settingsRepository)
    private val avsubtitlesSubtitleRepository = AvsubtitlesSubtitleRepository(application, settingsRepository)
    private val xunleiSubtitleRepository = XunleiSubtitleRepository(application, settingsRepository)
    private val errorLog = RuntimeErrorLog(application)
    private val subtitleRecognizer = SherpaOnnxSubtitleRecognizer(application, settingsRepository)
    private var mediaKey = videoUri.toString()
    private var subtitleStorageSourceUri: Uri? = null
    private val progressPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var initialSubtitleJob: Job? = null
    private var savedSubtitleJob: Job? = null
    private var liveSubtitleClearJob: Job? = null
    private var pendingJavzimuAction: PendingJavzimuAction? = null
    private var lastTranslatedSource = ""
    private var lastTranslationStartedAtMs = 0L
    private var lastPersistedPositionMs = Long.MIN_VALUE
    private var lastPersistedDurationMs = 0L
    private var lastPersistedAtMs = 0L
    private var retriedAfterForbidden = false
    private val vrModeSettings = VrModeSettings(application)

    val title: String = title.ifBlank { "Movie" }

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            isLoading = true,
            loadingMessage = initialPlaybackLoadingMessage(),
            externalSubtitleStyle = externalSubtitleStyleSettings(),
            vrMode = vrModeSettings.getMode(mediaKey),
            vrControlMode = vrModeSettings.getControlMode(mediaKey)
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState

    val playbackSpeed: Float
        get() = speeds[speedIndex]

    init {
        viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            showLoading(initialPlaybackLoadingMessage())
            resolvePlayback()
                .onSuccess { request ->
                    val resolveElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    if (resolveElapsedMs >= SLOW_PLAYBACK_RESOLVE_LOG_MS) {
                        logPlaybackStage("player.resolve.slow", resolveElapsedMs)
                    }
                    showLoading("正在启动播放器...")
                    val subtitleFeaturesEnabled = canUseLibrarySubtitleFeatures(request)
                    val resumePositionMs = playbackProgressRepository.getResumePosition(mediaKey)
                    val player = createPlayer(request, resumePositionMs)
                    _uiState.value = PlayerUiState(
                        playbackRequest = request,
                        player = player,
                        isLoading = false,
                        subtitleFeaturesEnabled = subtitleFeaturesEnabled,
                        externalSubtitleStyle = externalSubtitleStyleSettings(),
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                    if (subtitleFeaturesEnabled) {
                        loadInitialExternalSubtitles(request)
                    }
                }
                .onFailure { error ->
                    logPlaybackStage(
                        event = "player.resolve.failed",
                        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                        error = error
                    )
                    _uiState.value = PlayerUiState(
                        isLoading = false,
                        externalSubtitleStyle = externalSubtitleStyleSettings(),
                        errorMessage = playbackResolveErrorMessage(error)
                    )
                }
        }
    }

    private fun createPlayer(request: PlaybackRequest, resumePositionMs: Long, subtitle: LocalSubtitleFile? = null): ExoPlayer {
        val userAgent = request.userAgent ?: DEFAULT_USER_AGENT
        val headers = buildMap {
            putAll(request.headers)
            if (request.mediaUri.startsWith("http", ignoreCase = true)) {
                put("User-Agent", userAgent)
            }
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(application, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(application)
            .setRenderersFactory(SubtitleRenderersFactory(application, subtitleRecognizer))
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                    true
                )
                setMediaItem(createMediaItem(request, subtitle), resumePositionMs.coerceAtLeast(0L))
                prepare()
                playWhenReady = true
                addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (!isPlaying) saveProgress(this@apply)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_ENDED -> clearProgress()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            saveProgress(this@apply)
                            if (error.isHttp403() && request.pickcode != null && !retriedAfterForbidden) {
                                retryAfterForbidden(request.pickcode)
                                return
                            }
                            _uiState.update {
                                it.copy(errorMessage = error.message ?: error.errorCodeName)
                            }
                        }
                    }
                )
                startProgressSaver(this)
            }
    }

    private fun createMediaItem(request: PlaybackRequest, subtitle: LocalSubtitleFile?): MediaItem {
        val builder = MediaItem.Builder().setUri(request.mediaUri)
        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                        .setMimeType(subtitle.mimeType())
                        .setLanguage("zh")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder.build()
    }

    private fun externalSubtitleStyleSettings(): ExternalSubtitleStyleSettings =
        ExternalSubtitleStyleSettings(
            fontSizeSp = settingsRepository.getExternalSubtitleFontSizeSp(),
            bottomPaddingPercent = settingsRepository.getExternalSubtitleBottomPaddingPercent(),
            backgroundAlphaPercent = settingsRepository.getExternalSubtitleBackgroundAlphaPercent()
        )

    private suspend fun resolvePlayback(forceRefresh: Boolean = false): Result<PlaybackRequest> =
        try {
            Result.success(
                withTimeout(PLAYBACK_RESOLVE_TIMEOUT_MS) {
                    resolver.resolve(videoUri.toString(), title, fileName, forceRefresh)
                        .getOrThrow()
                }
            )
        } catch (error: TimeoutCancellationException) {
            Result.failure(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private fun playbackResolveErrorMessage(error: Throwable, refreshing: Boolean = false): String =
        when (error) {
            is TimeoutCancellationException,
            is SocketTimeoutException -> "解析播放地址超时，请检查网络、代理或 115 登录状态后再试"
            is IOException -> "解析播放地址失败，请检查网络、代理或 115 登录状态后再试：${error.message ?: "网络请求失败"}"
            else -> error.message ?: if (refreshing) "刷新 115 播放地址失败" else "解析播放地址失败"
        }

    private fun showLoading(message: String, clearPlayer: Boolean = false) {
        _uiState.update {
            it.copy(
                playbackRequest = if (clearPlayer) null else it.playbackRequest,
                player = if (clearPlayer) null else it.player,
                isLoading = true,
                errorMessage = null,
                loadingMessage = message
            )
        }
    }

    private fun initialPlaybackLoadingMessage(forceRefresh: Boolean = false): String =
        when {
            PickcodeExtractor.extract(videoUri.toString()) != null -> {
                if (forceRefresh) "正在刷新 115 播放直链..." else "正在向 115 请求播放直链..."
            }
            isStrmSource(videoUri, fileName) -> "正在读取 STRM 播放入口..."
            else -> "正在解析播放地址..."
        }

    private suspend fun canUseLibrarySubtitleFeatures(request: PlaybackRequest): Boolean {
        if (isStrmSource(videoUri, fileName)) return true
        val pickcode = request.pickcode
            ?: PickcodeExtractor.extract(videoUri.toString())
            ?: return true
        return cloudStrmRecordRepository.getCached(pickcode) != null
    }

    private fun loadInitialExternalSubtitles(request: PlaybackRequest) {
        initialSubtitleJob?.cancel()
        if (!_uiState.value.subtitleFeaturesEnabled) return
        initialSubtitleJob = viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            runCatching {
                val resolvedStorageUri = resolveSubtitleStorageSourceUri(request)
                subtitleStorageSourceUri = resolvedStorageUri
                mediaKey = resolvedStorageUri?.toString() ?: mediaKey
                val localSubtitles = listLocalExternalSubtitles()
                val preferredSubtitle = preferredExternalSubtitle(localSubtitles)
                localSubtitles to preferredSubtitle
            }.onSuccess { (localSubtitles, preferredSubtitle) ->
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                if (elapsedMs >= SLOW_SUBTITLE_LOAD_LOG_MS) {
                    logPlaybackStage("player.subtitle.scan.slow", elapsedMs)
                }
                _uiState.update {
                    it.copy(
                        localSubtitles = localSubtitles,
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                }
                preferredSubtitle?.let { applyExternalSubtitle(it, closePanel = false) }
            }.onFailure { error ->
                logPlaybackStage(
                    event = "player.subtitle.scan.failed",
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    error = error
                )
            }
        }
    }

    private fun logPlaybackStage(
        event: String,
        elapsedMs: Long,
        extra: Map<String, String?> = emptyMap(),
        error: Throwable? = null
    ) {
        errorLog.append(
            event = event,
            details = playbackLogDetails() + mapOf("elapsedMs" to elapsedMs.toString()) + extra,
            error = error
        )
    }

    private fun playbackLogDetails(): Map<String, String?> =
        mapOf(
            "source" to playbackSourceLabel(),
            "videoUri" to videoUri.toString().take(600),
            "fileName" to fileName,
            "title" to title
        )

    private fun playbackSourceLabel(): String =
        when {
            PickcodeExtractor.extract(videoUri.toString()) != null -> "cloud115"
            isStrmSource(videoUri, fileName) -> "strm"
            else -> videoUri.scheme.orEmpty().ifBlank { "direct" }
        }

    private fun retryAfterForbidden(pickcode: String) {
        retriedAfterForbidden = true
        viewModelScope.launch {
            val previousPlayer = _uiState.value.player
            val resumePositionMs = previousPlayer?.currentPosition?.coerceAtLeast(0L)
                ?: playbackProgressRepository.getResumePosition(mediaKey)
            previousPlayer?.let { persistProgress(it, force = true) }
            progressJob?.cancel()
            initialSubtitleJob?.cancel()
            previousPlayer?.release()
            showLoading(initialPlaybackLoadingMessage(forceRefresh = true), clearPlayer = true)
            resolver.invalidatePickcode(pickcode)
            val startedAtMs = SystemClock.elapsedRealtime()
            resolvePlayback(forceRefresh = true)
                .onSuccess { request ->
                    val resolveElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    if (resolveElapsedMs >= SLOW_PLAYBACK_RESOLVE_LOG_MS) {
                        logPlaybackStage("player.resolve.refresh.slow", resolveElapsedMs)
                    }
                    showLoading("正在重新启动播放器...")
                    val subtitleFeaturesEnabled = canUseLibrarySubtitleFeatures(request)
                    val player = createPlayer(request, resumePositionMs)
                    _uiState.value = PlayerUiState(
                        playbackRequest = request,
                        player = player,
                        isLoading = false,
                        subtitleFeaturesEnabled = subtitleFeaturesEnabled,
                        externalSubtitleStyle = externalSubtitleStyleSettings(),
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                    if (subtitleFeaturesEnabled) {
                        loadInitialExternalSubtitles(request)
                    }
                }
                .onFailure { error ->
                    logPlaybackStage(
                        event = "player.resolve.refresh.failed",
                        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                        error = error
                    )
                    _uiState.value = PlayerUiState(
                        isLoading = false,
                        externalSubtitleStyle = externalSubtitleStyleSettings(),
                        errorMessage = playbackResolveErrorMessage(error, refreshing = true)
                    )
                }
        }
    }

    private fun PlaybackException.isHttp403(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val invalidResponse = current as? HttpDataSource.InvalidResponseCodeException
            if (invalidResponse?.responseCode == 403) return true
            current = current.cause
        }
        return false
    }

    fun togglePlayPause() {
        uiState.value.player?.let { player ->
            if (player.isPlaying) {
                saveProgress(player)
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun leavePlayer() {
        val player = uiState.value.player
        if (player != null) {
            saveProgress(player)
            player.pause()
        }
        stopLiveSubtitleRecognition()
    }

    fun seekBack() {
        uiState.value.player?.let { player ->
            player.seekBack()
            saveProgress(player)
        }
    }

    fun seekForward() {
        uiState.value.player?.let { player ->
            player.seekForward()
            saveProgress(player)
        }
    }

    fun seekTo(positionMs: Long) {
        uiState.value.player?.let { player ->
            player.seekTo(positionMs.coerceAtLeast(0L))
            saveProgress(player)
        }
    }

    fun cycleSpeed(): Float {
        speedIndex = (speedIndex + 1) % speeds.size
        uiState.value.player?.setPlaybackSpeed(playbackSpeed)
        return playbackSpeed
    }

    fun hasLiveSubtitleModel(): Boolean = subtitleRecognizer.hasModelAssets()

    fun isPlayerLiveSubtitleEnabled(): Boolean = settingsRepository.isPlayerLiveSubtitleEnabled()

    fun openExternalSubtitlePanel(videoDurationMs: Long) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        val number = normalizeMovieNumber(fileName) ?: normalizeMovieNumber(title)
        val provider = settingsRepository.getSubtitleSearchProvider()
        val subtitleNumber = number?.let { normalizeSubtitleSearchNumber(it, provider) }
        _uiState.update {
            it.copy(
                externalSubtitlePanelVisible = true,
                externalSubtitleQueryNumber = subtitleNumber.orEmpty(),
                externalSubtitleProviderLabel = provider.label,
                externalSubtitleMessage = null,
                externalSubtitleError = null,
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                onlineSubtitles = emptyList()
            )
        }
        viewModelScope.launch {
            val storageSourceUri = resolveSubtitleStorageSourceUri()
            val localFiles = runCatching {
                javzimuSubtitleRepository.listLocalSubtitles(videoUri, fileName, storageSourceUri)
            }.getOrElse { emptyList() }
            _uiState.update { it.copy(localSubtitles = localFiles) }
            if (number == null) {
                _uiState.update {
                    it.copy(
                        externalSubtitleSearching = false,
                        externalSubtitleError = "\u6CA1\u6709\u8BC6\u522B\u5230\u5F71\u7247\u756A\u53F7\uFF0C\u65E0\u6CD5\u641C\u7D22\u5B57\u5E55"
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    externalSubtitleMessage = if (localFiles.isNotEmpty()) {
                        "\u5DF2\u627E\u5230\u672C\u5730\u5B57\u5E55\uFF0C\u53EF\u76F4\u63A5\u52A0\u8F7D\uFF1B\u4E5F\u53EF\u4EE5\u624B\u52A8\u641C\u7D22\u5728\u7EBF\u5B57\u5E55"
                    } else {
                        "\u5F53\u524D\u76EE\u5F55\u6CA1\u6709\u672C\u5730\u5B57\u5E55\uFF0C\u53EF\u624B\u52A8\u641C\u7D22\u5728\u7EBF\u5B57\u5E55"
                    },
                    externalSubtitleError = null
                )
            }
        }
    }

    fun searchExternalSubtitles(videoDurationMs: Long) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        val provider = settingsRepository.getSubtitleSearchProvider()
        val number = _uiState.value.externalSubtitleQueryNumber.ifBlank {
            (normalizeMovieNumber(fileName) ?: normalizeMovieNumber(title))
                ?.let { normalizeSubtitleSearchNumber(it, provider) }
                .orEmpty()
        }
        if (number.isBlank()) {
            _uiState.update {
                it.copy(
                    externalSubtitleSearching = false,
                    externalSubtitleError = "\u6CA1\u6709\u8BC6\u522B\u5230\u5F71\u7247\u756A\u53F7\uFF0C\u65E0\u6CD5\u641C\u7D22\u5B57\u5E55"
                )
            }
            return
        }
        if (_uiState.value.externalSubtitleSearching) return
        _uiState.update {
            it.copy(
                externalSubtitleSearching = true,
                externalSubtitleMessage = null,
                externalSubtitleError = null,
                onlineSubtitles = emptyList()
            )
        }
        viewModelScope.launch {
            runCatching {
                when (provider) {
                    SubtitleSearchProvider.Javzimu -> javzimuSubtitleRepository.search(number, videoDurationMs)
                    SubtitleSearchProvider.Avsubtitles -> avsubtitlesSubtitleRepository.search(number, videoDurationMs)
                    SubtitleSearchProvider.Xunlei -> xunleiSubtitleRepository.search(number, videoDurationMs)
                }
            }.onSuccess { results ->
                _uiState.update {
                    it.copy(
                        onlineSubtitles = results,
                        externalSubtitleSearching = false,
                        externalSubtitleMessage = if (results.isEmpty()) "\u6CA1\u6709\u627E\u5230\u65F6\u957F\u5339\u914D\u7684\u5B57\u5E55" else null,
                        externalSubtitleError = null
                    )
                }
            }.onFailure { error ->
                when (error) {
                    is JavzimuCloudflareException -> {
                        startJavzimuCookieRefresh(
                            action = PendingJavzimuAction.Search(videoDurationMs),
                            number = number
                        )
                        return@onFailure
                    }

                    is AvsubtitlesCloudflareException -> {
                        errorLog.append(
                            event = "player.avsubtitles.search.cloudflare",
                            details = mapOf("number" to number),
                            error = error
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        externalSubtitleSearching = false,
                        externalSubtitleError = error.message ?: "\u5B57\u5E55\u641C\u7D22\u5931\u8D25"
                    )
                }
            }
        }
    }

    fun dismissExternalSubtitlePanel() {
        _uiState.update { it.copy(externalSubtitlePanelVisible = false) }
    }

    fun loadLocalSubtitle(subtitle: LocalSubtitleFile) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        applyExternalSubtitle(subtitle)
    }

    fun deleteLocalSubtitle(subtitle: LocalSubtitleFile) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        viewModelScope.launch {
            runCatching {
                val wasActive = subtitle.name == _uiState.value.activeExternalSubtitleName
                if (wasActive) {
                    settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
                    clearExternalSubtitleTrack(closePanel = false)
                }
                val deleted = javzimuSubtitleRepository.deleteLocalSubtitle(subtitle)
                if (!deleted) error("字幕文件删除失败")
                listLocalExternalSubtitles()
            }.onSuccess { localFiles ->
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles,
                        externalSubtitleMessage = "已删除字幕：${subtitle.name}",
                        externalSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        externalSubtitleMessage = null,
                        externalSubtitleError = error.message ?: "字幕文件删除失败"
                    )
                }
            }
        }
    }

    fun setExternalSubtitleEnabled(enabled: Boolean) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (!enabled) {
            settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
            clearExternalSubtitleTrack(closePanel = false)
            return
        }
        val currentName = _uiState.value.activeExternalSubtitleName
        if (currentName != null) {
            settingsRepository.saveExternalSubtitleEnabled(mediaKey, true)
            _uiState.update { it.copy(externalSubtitleEnabled = true) }
            return
        }
        viewModelScope.launch {
            val localFiles = listLocalExternalSubtitles()
            val preferred = preferredExternalSubtitle(localFiles, requireEnabled = false)
                ?: localFiles.firstOrNull()
            if (preferred == null) {
                settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles,
                        externalSubtitleEnabled = false,
                        externalSubtitleError = "\u5F53\u524D\u6CA1\u6709\u53EF\u52A0\u8F7D\u7684\u672C\u5730\u5B57\u5E55"
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(localSubtitles = localFiles) }
            applyExternalSubtitle(preferred, closePanel = false)
        }
    }

    fun downloadAndLoadSubtitle(result: JavzimuSubtitleResult) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.externalSubtitleDownloading) return
        _uiState.update {
            it.copy(
                externalSubtitleDownloading = true,
                externalSubtitleMessage = "\u6B63\u5728\u4E0B\u8F7D\u5B57\u5E55...",
                externalSubtitleError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                val storageSourceUri = resolveSubtitleStorageSourceUri()
                when (result.provider) {
                    SubtitleSearchProvider.Javzimu -> javzimuSubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                    SubtitleSearchProvider.Avsubtitles -> avsubtitlesSubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                    SubtitleSearchProvider.Xunlei -> xunleiSubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                }
            }.onSuccess { subtitle ->
                val localFiles = runCatching {
                    javzimuSubtitleRepository.listLocalSubtitles(videoUri, fileName, resolveSubtitleStorageSourceUri())
                }.getOrElse { _uiState.value.localSubtitles + subtitle }
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles.distinctBy { file -> file.uri.toString() },
                        externalSubtitleDownloading = false,
                        externalSubtitleMessage = "\u5B57\u5E55\u5DF2\u4E0B\u8F7D\u5E76\u52A0\u8F7D\uFF1A${subtitle.name}",
                        externalSubtitleError = null
                    )
                }
                applyExternalSubtitle(subtitle)
            }.onFailure { error ->
                when (error) {
                    is JavzimuCloudflareException -> {
                        errorLog.append(
                            event = "player.javzimu.download.cloudflare",
                            details = subtitleLogDetails(result),
                            error = error
                        )
                        startJavzimuCookieRefresh(
                            action = PendingJavzimuAction.Download(result),
                            number = _uiState.value.externalSubtitleQueryNumber
                        )
                        return@onFailure
                    }

                    is AvsubtitlesCloudflareException -> {
                        errorLog.append(
                            event = "player.avsubtitles.download.cloudflare",
                            details = subtitleLogDetails(result),
                            error = error
                        )
                    }
                }
                errorLog.append(
                    event = "player.${result.provider.id}.download.failed",
                    details = subtitleLogDetails(result),
                    error = error
                )
                _uiState.update {
                    it.copy(
                        externalSubtitleDownloading = false,
                        externalSubtitleMessage = null,
                        externalSubtitleError = error.message ?: "\u5B57\u5E55\u4E0B\u8F7D\u5931\u8D25"
                    )
                }
            }
        }
    }

    private fun subtitleLogDetails(result: JavzimuSubtitleResult): Map<String, String?> =
        mapOf(
            "videoUri" to videoUri.toString(),
            "fileName" to fileName,
            "title" to title,
            "subtitleStorageSourceUri" to subtitleStorageSourceUri?.toString(),
            "queryNumber" to _uiState.value.externalSubtitleQueryNumber,
            "resultName" to result.name,
            "resultExt" to result.ext,
            "provider" to result.provider.id,
            "hasJavzimuCookie" to settingsRepository.getJavzimuCookies().isNotBlank().toString(),
            "javzimuCookieLength" to settingsRepository.getJavzimuCookies().length.toString(),
            "hasAvsubtitlesCookie" to settingsRepository.getAvsubtitlesCookies().isNotBlank().toString(),
            "avsubtitlesCookieLength" to settingsRepository.getAvsubtitlesCookies().length.toString()
        )
    private suspend fun listLocalExternalSubtitles(): List<LocalSubtitleFile> =
        runCatching {
            javzimuSubtitleRepository.listLocalSubtitles(videoUri, fileName, resolveSubtitleStorageSourceUri())
        }.getOrElse { emptyList() }

    private fun preferredExternalSubtitle(
        localFiles: List<LocalSubtitleFile>,
        requireEnabled: Boolean = true
    ): LocalSubtitleFile? {
        if (requireEnabled && !settingsRepository.isExternalSubtitleEnabled(mediaKey)) return null
        val preferredName = settingsRepository.getPreferredExternalSubtitleName(mediaKey)
            .takeIf { it.isNotBlank() }
            ?: return null
        return localFiles.firstOrNull { it.name == preferredName }
    }

    private suspend fun resolveSubtitleStorageSourceUri(): Uri? =
        subtitleStorageSourceUri ?: resolveSubtitleStorageSourceUri(_uiState.value.playbackRequest).also {
            if (it != null) subtitleStorageSourceUri = it
        }

    private suspend fun resolveSubtitleStorageSourceUri(request: PlaybackRequest?): Uri? {
        if (isStrmSource(videoUri, fileName)) return videoUri
        val pickcode = request?.pickcode
            ?: PickcodeExtractor.extract(videoUri.toString())
            ?: return null
        val strmUri = cloudStrmRecordRepository.getCached(pickcode)?.strmUri
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Uri.parse(strmUri) }.getOrNull()
    }

    private fun isStrmSource(uri: Uri, name: String): Boolean =
        name.substringAfterLast('.', "").equals("strm", ignoreCase = true) ||
            uri.toString().substringBefore('?').endsWith(".strm", ignoreCase = true)

    fun onJavzimuCookieReady(cookie: String) {
        settingsRepository.saveJavzimuCookies(cookie)
        val action = pendingJavzimuAction
        pendingJavzimuAction = null
        _uiState.update {
            it.copy(
                externalSubtitleMessage = "\u5DF2\u83B7\u53D6 Javzimu Cookie\uFF0C\u6B63\u5728\u91CD\u8BD5",
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                externalSubtitleError = null
            )
        }
        when (action) {
            is PendingJavzimuAction.Search -> searchExternalSubtitles(action.videoDurationMs)
            is PendingJavzimuAction.Download -> downloadAndLoadSubtitle(action.result)
            null -> Unit
        }
    }

    fun onJavzimuCookieFailed(message: String) {
        pendingJavzimuAction = null
        _uiState.update {
            it.copy(
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                externalSubtitleError = message
            )
        }
    }

    private fun startJavzimuCookieRefresh(action: PendingJavzimuAction, number: String) {
        pendingJavzimuAction = action
        val normalized = normalizeJavzimuSubtitleNumber(number)
        _uiState.update {
            it.copy(
                javzimuWebUrl = "https://javzimu.com/search/$normalized",
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                externalSubtitleMessage = "\u8BF7\u5728 Javzimu WebView \u4E2D\u5B8C\u6210\u9A8C\u8BC1\u540E\u8FD4\u56DE\uFF0C\u4F1A\u81EA\u52A8\u91CD\u8BD5",
                externalSubtitleError = null
            )
        }
    }

    fun consumeJavzimuWebUrl(): String? {
        val url = _uiState.value.javzimuWebUrl
        if (url != null) {
            _uiState.update { it.copy(javzimuWebUrl = null) }
        }
        return url
    }

    private fun applyExternalSubtitle(subtitle: LocalSubtitleFile, closePanel: Boolean = true) {
        stopLiveSubtitleRecognition()
        val request = _uiState.value.playbackRequest ?: return
        val player = _uiState.value.player ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        player.setMediaItem(createMediaItem(request, subtitle), position)
        player.prepare()
        player.playWhenReady = wasPlaying
        settingsRepository.savePreferredExternalSubtitle(mediaKey, subtitle.name, enabled = true)
        _uiState.update {
            it.copy(
                activeExternalSubtitleName = subtitle.name,
                externalSubtitleEnabled = true,
                externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                externalSubtitleMessage = null,
                externalSubtitleError = null
            )
        }
    }

    private fun clearExternalSubtitleTrack(closePanel: Boolean = true) {
        if (_uiState.value.activeExternalSubtitleName == null) {
            _uiState.update {
                it.copy(
                    externalSubtitleEnabled = false,
                    externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                    externalSubtitleMessage = null,
                    externalSubtitleError = null
                )
            }
            return
        }
        val request = _uiState.value.playbackRequest ?: return
        val player = _uiState.value.player ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        player.setMediaItem(createMediaItem(request, null), position)
        player.prepare()
        player.playWhenReady = wasPlaying
        _uiState.update {
            it.copy(
                activeExternalSubtitleName = null,
                externalSubtitleEnabled = false,
                externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                externalSubtitleMessage = null,
                externalSubtitleError = null
            )
        }
    }

    fun setVrMode(mode: VrMode) {
        vrModeSettings.saveMode(mediaKey, mode)
        _uiState.update { it.copy(vrMode = mode) }
    }

    fun setVrControlMode(mode: VrControlMode) {
        vrModeSettings.saveControlMode(mediaKey, mode)
        _uiState.update { it.copy(vrControlMode = mode) }
    }

    fun runRealtimeSubtitleTest() {
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleSourceText = "\u3053\u308C\u306F\u30EA\u30A2\u30EB\u30BF\u30A4\u30E0\u5B57\u5E55\u7FFB\u8A33\u306E\u30C6\u30B9\u30C8\u3067\u3059\u3002",
                liveSubtitleTranslatedText = "\u6B63\u5728\u8C03\u7528\u7FFB\u8BD1...",
                liveSubtitleError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                translateClient.translate("\u3053\u308C\u306F\u30EA\u30A2\u30EB\u30BF\u30A4\u30E0\u5B57\u5E55\u7FFB\u8A33\u306E\u30C6\u30B9\u30C8\u3067\u3059\u3002", from = "jp", to = "zh")
            }.onSuccess { translated ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = translated.ifBlank { "\u7FFB\u8BD1\u7ED3\u679C\u4E3A\u7A7A" },
                        liveSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = "",
                        liveSubtitleError = error.message ?: "\u7FFB\u8BD1\u5931\u8D25"
                    )
                }
            }
        }
    }

    fun toggleLiveSubtitleRecognition() {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.liveSubtitleEnabled) {
            stopLiveSubtitleRecognition()
        } else {
            startLiveSubtitleRecognition()
        }
    }

    fun startLiveSubtitleRecognition() {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        startLiveSubtitleFromMicrophone()
    }

    fun startLiveSubtitleFromMicrophone() {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.liveSubtitleListening) return
        if (!subtitleRecognizer.hasModelAssets()) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = false,
                    liveSubtitleSourceText = "\u5B9E\u65F6\u5B57\u5E55\u672A\u542F\u52A8",
                    liveSubtitleTranslatedText = "",
                    liveSubtitleError = "\u672A\u627E\u5230 sherpa-onnx SenseVoice \u6A21\u578B"
                )
            }
            return
        }
        clearExternalSubtitleTrack()
        _uiState.update { state ->
            state.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = "\u6B63\u5728\u542F\u52A8 ASR...",
                liveSubtitleTranslatedText = state.liveSubtitleTranslatedText,
                liveSubtitleError = null
            )
        }
        subtitleRecognizer.startFromMicrophone(
            onStatus = { message ->
                _uiState.update {
                    it.copy(
                        liveSubtitleEnabled = true,
                        liveSubtitleListening = true,
                        liveSubtitleSourceText = message,
                        liveSubtitleError = null
                    )
                }
            },
            onText = { text ->
                translateRecognizedSubtitleAndSave(text)
            },
            onError = { message ->
                _uiState.update {
                    it.copy(
                        liveSubtitleEnabled = true,
                        liveSubtitleListening = false,
                        liveSubtitleError = message
                    )
                }
            }
        )
    }

    fun startLiveSubtitleFromPlayerAudio() {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.liveSubtitleListening) return
        clearExternalSubtitleTrack()
        savedSubtitleJob?.cancel()
        viewModelScope.launch {
            val savedCues = liveSubtitleStore.load(videoUri, fileName)
            if (savedCues.isNotEmpty()) {
                startSavedSubtitlePlayback(savedCues)
            } else {
                startLiveSubtitleCaptureFromPlayerAudio()
            }
        }
    }

    private fun startLiveSubtitleCaptureFromPlayerAudio() {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.liveSubtitleListening) return
        if (!subtitleRecognizer.hasModelAssets()) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = false,
                    liveSubtitleSourceText = "\u5B9E\u65F6\u5B57\u5E55\u672A\u542F\u52A8",
                    liveSubtitleTranslatedText = "",
                    liveSubtitleError = "\u672A\u627E\u5230 sherpa-onnx SenseVoice \u6A21\u578B"
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = "",
                liveSubtitleTranslatedText = state.liveSubtitleTranslatedText,
                liveSubtitleError = null
            )
        }
        subtitleRecognizer.startFromPlayerPcm(
            onStatus = { message ->
                _uiState.update {
                    it.copy(
                        liveSubtitleEnabled = true,
                        liveSubtitleListening = true,
                        liveSubtitleSourceText = "",
                        liveSubtitleError = null
                    )
                }
            },
            onText = { text ->
                translateRecognizedSubtitleAndSave(text)
            },
            onError = { message ->
                _uiState.update {
                    it.copy(
                        liveSubtitleEnabled = true,
                        liveSubtitleListening = false,
                        liveSubtitleError = message
                    )
                }
            }
        )
    }

    fun stopLiveSubtitleRecognition() {
        savedSubtitleJob?.cancel()
        savedSubtitleJob = null
        liveSubtitleClearJob?.cancel()
        liveSubtitleClearJob = null
        subtitleRecognizer.stop()
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = false,
                liveSubtitleListening = false,
                liveSubtitleSourceText = "",
                liveSubtitleTranslatedText = "",
                liveSubtitleError = null
            )
        }
    }

    private fun startSavedSubtitlePlayback(cues: List<SavedSubtitleCue>) {
        subtitleRecognizer.stop()
        savedSubtitleJob?.cancel()
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = false,
                liveSubtitleSourceText = "",
                liveSubtitleTranslatedText = "",
                liveSubtitleError = null
            )
        }
        savedSubtitleJob = viewModelScope.launch {
            var lastCue: SavedSubtitleCue? = null
            while (isActive && _uiState.value.liveSubtitleEnabled) {
                val positionMs = _uiState.value.player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                val cue = cues.lastOrNull { positionMs in it.startMs..it.endMs }
                if (cue != lastCue) {
                    lastCue = cue
                    _uiState.update {
                        it.copy(
                            liveSubtitleSourceText = cue?.sourceText.orEmpty(),
                            liveSubtitleTranslatedText = cue?.translatedText.orEmpty(),
                            liveSubtitleError = null
                        )
                    }
                }
                delay(250)
            }
        }
    }

    fun onLiveSubtitlePermissionDenied() {
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = false,
                liveSubtitleError = "\u5F53\u524D\u8BBE\u5907\u4E0D\u652F\u6301\u7CFB\u7EDF\u5185\u5F55\u6743\u9650\uFF0C\u8BF7\u4F7F\u7528 sherpa-onnx \u6A21\u578B\u540E\u518D\u8BD5"
            )
        }
    }

    private fun translateRecognizedSubtitleAndSave(text: String) {
        val cleanText = text.trim()
        if (cleanText.length < 2 || cleanText == lastTranslatedSource) return
        val now = System.currentTimeMillis()
        if (now - lastTranslationStartedAtMs < LIVE_TRANSLATE_MIN_INTERVAL_MS) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = true,
                    liveSubtitleSourceText = cleanText,
                    liveSubtitleTranslatedText = "",
                    liveSubtitleError = null
                )
            }
            scheduleLiveSubtitleClear(cleanText, "")
            return
        }
        lastTranslationStartedAtMs = now
        lastTranslatedSource = cleanText
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = cleanText,
                liveSubtitleTranslatedText = "",
                liveSubtitleError = null
            )
        }
        scheduleLiveSubtitleClear(cleanText, "")
        viewModelScope.launch {
            runCatching {
                translateClient.translate(cleanText, from = "jp", to = "zh")
            }.onSuccess { translated ->
                val finalTranslated = translated.trim().ifBlank { "\u672A\u8BC6\u522B\u5230\u6709\u6548\u7FFB\u8BD1" }
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = finalTranslated,
                        liveSubtitleError = null
                    )
                }
                scheduleLiveSubtitleClear(cleanText, finalTranslated)
                liveSubtitleStore.append(
                    videoUri = videoUri,
                    fileName = fileName,
                    sourceText = cleanText,
                    translatedText = finalTranslated,
                    positionMs = _uiState.value.player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = "",
                        liveSubtitleError = error.message ?: "\u7FFB\u8BD1\u5931\u8D25"
                    )
                }
            }
        }
    }

    private fun translateRecognizedSubtitle(text: String) {
        val cleanText = text.trim()
        if (cleanText.length < 2 || cleanText == lastTranslatedSource) return
        val now = System.currentTimeMillis()
        if (now - lastTranslationStartedAtMs < LIVE_TRANSLATE_MIN_INTERVAL_MS) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = true,
                    liveSubtitleSourceText = cleanText,
                    liveSubtitleError = null
                )
            }
            scheduleLiveSubtitleClear(cleanText, _uiState.value.liveSubtitleTranslatedText)
            return
        }
        lastTranslationStartedAtMs = now
        lastTranslatedSource = cleanText
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = cleanText,
                liveSubtitleError = null
            )
        }
        scheduleLiveSubtitleClear(cleanText, _uiState.value.liveSubtitleTranslatedText)
        viewModelScope.launch {
            runCatching {
                translateClient.translate(cleanText, from = "jp", to = "zh")
            }.onSuccess { translated ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = translated.ifBlank { "\u7FFB\u8BD1\u7ED3\u679C\u4E3A\u7A7A" },
                        liveSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = "",
                        liveSubtitleError = error.message ?: "\u7FFB\u8BD1\u5931\u8D25"
                    )
                }
            }
        }
    }

    private fun scheduleLiveSubtitleClear(sourceText: String, translatedText: String) {
        if (sourceText.isBlank() && translatedText.isBlank()) return
        liveSubtitleClearJob?.cancel()
        liveSubtitleClearJob = viewModelScope.launch {
            delay(LIVE_SUBTITLE_VISIBLE_MS)
            _uiState.update { state ->
                val sameSource = sourceText.isBlank() || state.liveSubtitleSourceText == sourceText
                val sameTranslated = translatedText.isBlank() || state.liveSubtitleTranslatedText == translatedText
                if (state.liveSubtitleEnabled && state.liveSubtitleError == null && sameSource && sameTranslated) {
                    state.copy(
                        liveSubtitleSourceText = "",
                        liveSubtitleTranslatedText = ""
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun startProgressSaver(player: Player) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                persistProgress(player, force = false)
            }
        }
    }

    private fun saveProgress(player: Player) {
        val playbackState = player.playbackState
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        progressPersistenceScope.launch {
            persistProgressValues(playbackState, positionMs, durationMs, force = true)
        }
    }

    private suspend fun persistProgress(player: Player, force: Boolean) {
        persistProgressValues(
            playbackState = player.playbackState,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            force = force
        )
    }

    private suspend fun persistProgressValues(
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean
    ) {
        if (playbackState == Player.STATE_ENDED) {
            playbackProgressRepository.clear(mediaKey)
            lastPersistedPositionMs = Long.MIN_VALUE
            lastPersistedDurationMs = 0L
            lastPersistedAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (!force) {
            if (positionMs < MIN_AUTOSAVE_POSITION_MS) return
            val positionDelta = if (lastPersistedPositionMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(positionMs - lastPersistedPositionMs)
            val durationStable = abs(durationMs - lastPersistedDurationMs) <= 1_000L
            val intervalNotReached = now - lastPersistedAtMs < PROGRESS_SAVE_INTERVAL_MS
            if (positionDelta < PROGRESS_SAVE_POSITION_DELTA_MS && durationStable && intervalNotReached) return
        }
        playbackProgressRepository.save(
            mediaKey = mediaKey,
            positionMs = positionMs,
            durationMs = durationMs
        )
        lastPersistedPositionMs = positionMs
        lastPersistedDurationMs = durationMs
        lastPersistedAtMs = now
    }

    private fun clearProgress() {
        progressPersistenceScope.launch {
            playbackProgressRepository.clear(mediaKey)
            lastPersistedPositionMs = Long.MIN_VALUE
            lastPersistedDurationMs = 0L
            lastPersistedAtMs = 0L
        }
    }

    override fun onCleared() {
        val player = uiState.value.player
        progressJob?.cancel()
        initialSubtitleJob?.cancel()
        savedSubtitleJob?.cancel()
        liveSubtitleClearJob?.cancel()
        subtitleRecognizer.release()
        if (player != null) {
            player.release()
        }
    }

    companion object {
        private const val LIVE_TRANSLATE_MIN_INTERVAL_MS = 1_200L
        private const val LIVE_SUBTITLE_VISIBLE_MS = 4_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val PROGRESS_SAVE_POSITION_DELTA_MS = 10_000L
        private const val MIN_AUTOSAVE_POSITION_MS = 5_000L
        private const val PLAYBACK_RESOLVE_TIMEOUT_MS = 45_000L
        private const val SLOW_PLAYBACK_RESOLVE_LOG_MS = 8_000L
        private const val SLOW_SUBTITLE_LOAD_LOG_MS = 3_000L

        fun factory(
            application: Application,
            videoUri: Uri,
            title: String,
            fileName: String,
            directLinkRepository: DirectLinkRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            settingsRepository: AppSettingsRepository,
            playbackProgressRepository: PlaybackProgressRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(
                        application = application,
                        videoUri = videoUri,
                        title = title,
                        fileName = fileName,
                        directLinkRepository = directLinkRepository,
                        cloudStrmRecordRepository = cloudStrmRecordRepository,
                        settingsRepository = settingsRepository,
                        playbackProgressRepository = playbackProgressRepository
                    ) as T
            }
    }
}

data class PlayerUiState(
    val playbackRequest: PlaybackRequest? = null,
    val player: ExoPlayer? = null,
    val externalSubtitleStyle: ExternalSubtitleStyleSettings = ExternalSubtitleStyleSettings(),
    val vrMode: VrMode = VrMode.Normal2D,
    val vrControlMode: VrControlMode = VrControlMode.TouchAndSensor,
    val externalSubtitlePanelVisible: Boolean = false,
    val externalSubtitleSearching: Boolean = false,
    val externalSubtitleDownloading: Boolean = false,
    val externalSubtitleQueryNumber: String = "",
    val externalSubtitleProviderLabel: String = SubtitleSearchProvider.Javzimu.label,
    val localSubtitles: List<LocalSubtitleFile> = emptyList(),
    val onlineSubtitles: List<JavzimuSubtitleResult> = emptyList(),
    val externalSubtitleEnabled: Boolean = false,
    val activeExternalSubtitleName: String? = null,
    val externalSubtitleMessage: String? = null,
    val externalSubtitleError: String? = null,
    val javzimuWebUrl: String? = null,
    val liveSubtitleEnabled: Boolean = false,
    val liveSubtitleListening: Boolean = false,
    val liveSubtitleSourceText: String = "",
    val liveSubtitleTranslatedText: String = "",
    val liveSubtitleError: String? = null,
    val subtitleFeaturesEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val errorMessage: String? = null
)

data class ExternalSubtitleStyleSettings(
    val fontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val bottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val backgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT
)

private sealed interface PendingJavzimuAction {
    data class Search(val videoDurationMs: Long) : PendingJavzimuAction
    data class Download(val result: JavzimuSubtitleResult) : PendingJavzimuAction
}

private fun normalizeSubtitleSearchNumber(number: String, provider: SubtitleSearchProvider): String =
    when (provider) {
        SubtitleSearchProvider.Xunlei -> normalizeXunleiSubtitleNumber(number)
        SubtitleSearchProvider.Javzimu,
        SubtitleSearchProvider.Avsubtitles -> normalizeJavzimuSubtitleNumber(number)
    }

private fun LocalSubtitleFile.mimeType(): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

