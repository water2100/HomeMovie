package com.example.localmovielibrary.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.localmovielibrary.asr.SherpaOnnxSubtitleRecognizer
import com.example.localmovielibrary.data.repository.DirectLinkRepository
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.PlaybackProgressRepository
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import com.example.localmovielibrary.playback.PlaybackRequest
import com.example.localmovielibrary.playback.PlaybackResolver
import com.example.localmovielibrary.playback.SubtitleRenderersFactory
import com.example.localmovielibrary.playback.vr.VrControlMode
import com.example.localmovielibrary.playback.vr.VrMode
import com.example.localmovielibrary.playback.vr.VrModeSettings
import com.example.localmovielibrary.translate.BaiduTranslateClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlayerViewModel(
    private val application: Application,
    private val videoUri: Uri,
    title: String,
    private val fileName: String,
    directLinkRepository: DirectLinkRepository,
    settingsRepository: AppSettingsRepository,
    private val playbackProgressRepository: PlaybackProgressRepository
) : AndroidViewModel(application) {
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1
    private val resolver = PlaybackResolver(application.contentResolver, directLinkRepository)
    private val translateClient = BaiduTranslateClient(settingsRepository)
    private val subtitleRecognizer = SherpaOnnxSubtitleRecognizer(application)
    private val mediaKey = videoUri.toString()
    private var progressJob: Job? = null
    private var lastTranslatedSource = ""
    private var retriedAfterForbidden = false
    private val vrModeSettings = VrModeSettings(application)

    val title: String = title.ifBlank { "Movie" }

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            isLoading = true,
            vrMode = vrModeSettings.getMode(mediaKey),
            vrControlMode = vrModeSettings.getControlMode(mediaKey)
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState

    val playbackSpeed: Float
        get() = speeds[speedIndex]

    init {
        viewModelScope.launch {
            resolver.resolve(videoUri.toString(), this@PlayerViewModel.title, fileName)
                .onSuccess { request ->
                    val resumePositionMs = playbackProgressRepository.getResumePosition(mediaKey)
                    val player = createPlayer(request, resumePositionMs)
                    _uiState.value = PlayerUiState(
                        playbackRequest = request,
                        player = player,
                        isLoading = false,
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                }
                .onFailure { error ->
                    _uiState.value = PlayerUiState(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to fetch playback address"
                    )
                }
        }
    }

    private fun createPlayer(request: PlaybackRequest, resumePositionMs: Long): ExoPlayer {
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
                setMediaItem(MediaItem.fromUri(request.mediaUri), resumePositionMs.coerceAtLeast(0L))
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

    private fun retryAfterForbidden(pickcode: String) {
        retriedAfterForbidden = true
        viewModelScope.launch {
            val previousPlayer = _uiState.value.player
            val resumePositionMs = previousPlayer?.currentPosition?.coerceAtLeast(0L)
                ?: playbackProgressRepository.getResumePosition(mediaKey)
            previousPlayer?.let { persistProgress(it) }
            progressJob?.cancel()
            previousPlayer?.release()
            _uiState.value = _uiState.value.copy(player = null, isLoading = true, errorMessage = null)
            resolver.invalidatePickcode(pickcode)
            resolver.resolve(videoUri.toString(), title, fileName, forceRefresh = true)
                .onSuccess { request ->
                    val player = createPlayer(request, resumePositionMs)
                    _uiState.value = PlayerUiState(
                        playbackRequest = request,
                        player = player,
                        isLoading = false,
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                }
                .onFailure { error ->
                    _uiState.value = PlayerUiState(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to refresh 115 playback address"
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
                liveSubtitleSourceText = "これはリアルタイム字幕翻訳のテストです。",
                liveSubtitleTranslatedText = "正在调用百度翻译...",
                liveSubtitleError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                translateClient.translate("これはリアルタイム字幕翻訳のテストです。", from = "jp", to = "zh")
            }.onSuccess { translated ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = translated.ifBlank { "百度翻译返回为空" },
                        liveSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = "",
                        liveSubtitleError = error.message ?: "百度翻译失败"
                    )
                }
            }
        }
    }

    fun toggleLiveSubtitleRecognition() {
        if (_uiState.value.liveSubtitleEnabled) {
            stopLiveSubtitleRecognition()
        } else {
            startLiveSubtitleRecognition()
        }
    }

    fun startLiveSubtitleRecognition() {
        startLiveSubtitleFromMicrophone()
    }

    fun startLiveSubtitleFromMicrophone() {
        if (_uiState.value.liveSubtitleListening) return
        if (!subtitleRecognizer.hasModelAssets()) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = false,
                    liveSubtitleSourceText = "实时字幕未启动",
                    liveSubtitleTranslatedText = "",
                    liveSubtitleError = "缺少 sherpa-onnx SenseVoice 模型文件"
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = "本地 ASR 正在启动...",
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
                translateRecognizedSubtitle(text)
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
        if (_uiState.value.liveSubtitleListening) return
        if (!subtitleRecognizer.hasModelAssets()) {
            _uiState.update {
                it.copy(
                    liveSubtitleEnabled = true,
                    liveSubtitleListening = false,
                    liveSubtitleSourceText = "实时字幕未启动",
                    liveSubtitleTranslatedText = "",
                    liveSubtitleError = "缺少 sherpa-onnx SenseVoice 模型文件"
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = "正在启动播放器内录 ASR...",
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
                        liveSubtitleSourceText = message,
                        liveSubtitleError = null
                    )
                }
            },
            onText = { text ->
                translateRecognizedSubtitle(text)
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

    fun onLiveSubtitlePermissionDenied() {
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = false,
                liveSubtitleError = "需要麦克风权限才能使用 sherpa-onnx 本地实时字幕"
            )
        }
    }

    private fun translateRecognizedSubtitle(text: String) {
        if (text == lastTranslatedSource) return
        lastTranslatedSource = text
        _uiState.update {
            it.copy(
                liveSubtitleEnabled = true,
                liveSubtitleListening = true,
                liveSubtitleSourceText = text,
                liveSubtitleTranslatedText = "正在翻译...",
                liveSubtitleError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                translateClient.translate(text, from = "jp", to = "zh")
            }.onSuccess { translated ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = translated.ifBlank { "百度翻译返回为空" },
                        liveSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        liveSubtitleTranslatedText = "",
                        liveSubtitleError = error.message ?: "百度翻译失败"
                    )
                }
            }
        }
    }

    private fun startProgressSaver(player: Player) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                persistProgress(player)
            }
        }
    }

    private fun saveProgress(player: Player) {
        viewModelScope.launch {
            persistProgress(player)
        }
    }

    private suspend fun persistProgress(player: Player) {
        if (player.playbackState == Player.STATE_ENDED) {
            playbackProgressRepository.clear(mediaKey)
            return
        }
        playbackProgressRepository.save(
            mediaKey = mediaKey,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
        )
    }

    private fun clearProgress() {
        viewModelScope.launch {
            playbackProgressRepository.clear(mediaKey)
        }
    }

    override fun onCleared() {
        val player = uiState.value.player
        progressJob?.cancel()
        subtitleRecognizer.release()
        if (player != null) {
            runBlocking { persistProgress(player) }
            player.release()
        }
    }

    companion object {
        fun factory(
            application: Application,
            videoUri: Uri,
            title: String,
            fileName: String,
            directLinkRepository: DirectLinkRepository,
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
                        settingsRepository = settingsRepository,
                        playbackProgressRepository = playbackProgressRepository
                    ) as T
            }
    }
}

data class PlayerUiState(
    val playbackRequest: PlaybackRequest? = null,
    val player: ExoPlayer? = null,
    val vrMode: VrMode = VrMode.Normal2D,
    val vrControlMode: VrControlMode = VrControlMode.TouchAndSensor,
    val liveSubtitleEnabled: Boolean = false,
    val liveSubtitleListening: Boolean = false,
    val liveSubtitleSourceText: String = "",
    val liveSubtitleTranslatedText: String = "",
    val liveSubtitleError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
