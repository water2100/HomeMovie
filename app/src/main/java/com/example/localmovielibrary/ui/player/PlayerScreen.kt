package com.example.localmovielibrary.ui.player

import android.app.Activity
import android.media.AudioManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.localmovielibrary.playback.vr.VrControlMode
import com.example.localmovielibrary.playback.vr.VrMode
import com.example.localmovielibrary.subtitle.JavzimuSubtitleResult
import com.example.localmovielibrary.subtitle.LocalSubtitleFile
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenJavzimuCookie: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player = uiState.player
    val vrMode = uiState.vrMode
    val vrControlMode = uiState.vrControlMode
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsLocked by rememberSaveable { mutableStateOf(false) }
    var isPlaying by rememberSaveable(player) { mutableStateOf(player?.isPlaying == true) }
    var positionMs by rememberSaveable(player) { mutableLongStateOf(player?.currentPosition?.coerceAtLeast(0L) ?: 0L) }
    var durationMs by rememberSaveable(player) { mutableLongStateOf(player?.duration?.takeIf { it > 0 } ?: 0L) }
    var isLandscape by rememberSaveable { mutableStateOf(true) }
    var speed by rememberSaveable { mutableFloatStateOf(viewModel.playbackSpeed) }
    var resizeModeIndex by rememberSaveable { mutableStateOf(0) }
    var audioTrackDialogVisible by rememberSaveable { mutableStateOf(false) }
    var playbackModePanelVisible by rememberSaveable { mutableStateOf(false) }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var gestureMode by remember { mutableStateOf<GestureMode?>(null) }
    var gestureStartSide by remember { mutableStateOf<GestureSide?>(null) }
    var gestureFeedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var seekDragStartPositionMs by remember { mutableLongStateOf(0L) }
    var seekDragTotalX by remember { mutableFloatStateOf(0f) }
    var seekDragTotalY by remember { mutableFloatStateOf(0f) }
    var seekDragPreviewMs by remember { mutableStateOf<Long?>(null) }
    var brightnessValue by remember(activity) {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f)
    }
    val originalScreenBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    var volumeValue by remember(audioManager) {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    fun restorePlayerBrightness() {
        val window = activity?.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = originalScreenBrightness
        window.attributes = attrs
    }

    val leavePlayer = {
        restorePlayerBrightness()
        viewModel.leavePlayer()
        onBack()
    }

    BackHandler(
        enabled = uiState.subtitleFeaturesEnabled && uiState.externalSubtitlePanelVisible,
        onBack = viewModel::dismissExternalSubtitlePanel
    )
    BackHandler(
        enabled = playbackModePanelVisible,
        onBack = { playbackModePanelVisible = false }
    )
    BackHandler(
        enabled = !uiState.externalSubtitlePanelVisible && !playbackModePanelVisible,
        onBack = leavePlayer
    )

    DisposableEffect(activity) {
        val window = activity?.window
        val hadKeepScreenOn = window
            ?.attributes
            ?.flags
            ?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (!hadKeepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            restorePlayerBrightness()
            viewModel.leavePlayer()
        }
    }

    if (player == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.errorMessage ?: uiState.loadingMessage ?: "正在解析播放地址...",
                color = if (uiState.errorMessage == null) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp)
            )
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = leavePlayer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp)
            )
        }
        return
    }

    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
            restorePlayerBrightness()
        }
    }

    DisposableEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                isPlaying = isPlayingValue
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.takeIf { it > 0 } ?: 0L
            }

            override fun onPlayerError(error: PlaybackException) {
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, vrMode, playbackModePanelVisible) {
        if (controlsVisible && isPlaying && !playbackModePanelVisible) {
            delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureFeedback?.version, gestureStartSide) {
        if (gestureFeedback != null && gestureStartSide == null) {
            delay(850)
            gestureFeedback = null
        }
    }

    LaunchedEffect(uiState.javzimuWebUrl) {
        uiState.javzimuWebUrl?.let { url ->
            viewModel.consumeJavzimuWebUrl()
            onOpenJavzimuCookie(url)
        }
    }

    fun setBrightness(value: Float) {
        val window = activity?.window ?: return
        brightnessValue = value.coerceIn(0.02f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = brightnessValue
        window.attributes = attrs
        gestureFeedback = GestureFeedback(
            side = GestureSide.Brightness,
            percent = (brightnessValue * 100f).roundToInt().coerceIn(0, 100),
            version = System.nanoTime()
        )
    }

    fun setVolume(value: Float) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeValue = value.coerceIn(0f, maxVolume.toFloat())
        val targetVolume = volumeValue.roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        gestureFeedback = GestureFeedback(
            side = GestureSide.Volume,
            percent = ((targetVolume.toFloat() / maxVolume.toFloat()) * 100f).roundToInt().coerceIn(0, 100),
            version = System.nanoTime()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (vrMode.isVr || controlsLocked) Modifier else Modifier.pointerInput(isLandscape, activity, audioManager) {
                detectDragGestures(
                    onDragStart = { offset ->
                        gestureMode = null
                        gestureStartSide = if (offset.x < size.width / 2f) GestureSide.Brightness else GestureSide.Volume
                        seekDragStartPositionMs = positionMs
                        seekDragTotalX = 0f
                        seekDragTotalY = 0f
                        seekDragPreviewMs = null
                    },
                    onDragEnd = {
                        seekDragPreviewMs?.let { target ->
                            viewModel.seekTo(target)
                            positionMs = target
                        }
                        seekDragPreviewMs = null
                        gestureMode = null
                        gestureStartSide = null
                    },
                    onDragCancel = {
                        seekDragPreviewMs = null
                        gestureMode = null
                        gestureStartSide = null
                    },
                    onDrag = { change, dragAmount ->
                        if (!isLandscape) return@detectDragGestures
                        seekDragTotalX += dragAmount.x
                        seekDragTotalY += dragAmount.y
                        if (gestureMode == null) {
                            val threshold = 18f
                            if (abs(seekDragTotalX) < threshold && abs(seekDragTotalY) < threshold) {
                                return@detectDragGestures
                            }
                            gestureMode = if (abs(seekDragTotalX) > abs(seekDragTotalY)) {
                                GestureMode.Seek
                            } else {
                                GestureMode.VerticalAdjust
                            }
                        }
                        if (gestureMode == GestureMode.Seek) {
                            if (durationMs <= 0L) return@detectDragGestures
                            val seekPerScreenMs = (durationMs * 0.10f)
                                .toLong()
                                .coerceIn(30_000L, 180_000L)
                            val deltaMs = ((seekDragTotalX / size.width.coerceAtLeast(1)) * seekPerScreenMs).toLong()
                            seekDragPreviewMs = (seekDragStartPositionMs + deltaMs).coerceIn(0L, durationMs)
                            controlsVisible = false
                        } else {
                            val delta = -dragAmount.y / size.height.coerceAtLeast(1)
                            when (gestureStartSide) {
                                GestureSide.Brightness -> {
                                    setBrightness(brightnessValue + delta * 1.15f)
                                    controlsVisible = true
                                }
                                GestureSide.Volume -> {
                                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    setVolume(volumeValue + delta * maxVolume * 1.25f)
                                    controlsVisible = true
                                }
                                null -> Unit
                            }
                        }
                    }
                )
            })
            .then(if (vrMode.isVr || controlsLocked) Modifier else Modifier.clickable { controlsVisible = !controlsVisible })
    ) {
        if (vrMode.isVr) {
            VrSphericalPlayerView(
                player = player,
                vrMode = vrMode,
                controlMode = vrControlMode,
                onTap = {
                    if (!controlsLocked) {
                        controlsVisible = !controlsVisible
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                        resizeMode = resizeModes[resizeModeIndex]
                        applyExternalSubtitleStyle(uiState.externalSubtitleStyle)
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = resizeModes[resizeModeIndex]
                    it.applyExternalSubtitleStyle(uiState.externalSubtitleStyle)
                }
            )
        }

        if (vrMode.isVr) {
            VrExternalSubtitleOverlay(
                player = player,
                enabled = uiState.externalSubtitleEnabled,
                style = uiState.externalSubtitleStyle,
                controlsVisible = controlsVisible,
                modifier = Modifier.fillMaxSize()
            )
        }

        GestureFeedbackOverlay(
            feedback = gestureFeedback,
            modifier = Modifier.align(Alignment.Center)
        )

        SeekDragFeedbackOverlay(
            targetPositionMs = seekDragPreviewMs,
            startPositionMs = seekDragStartPositionMs,
            modifier = Modifier.align(Alignment.Center)
        )

        AnimatedVisibility(
            visible = controlsVisible && !controlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerOverlay(
                title = viewModel.title,
                isPlaying = isPlaying,
                isLandscape = isLandscape,
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                speeds = viewModel.playbackSpeeds,
                errorMessage = uiState.errorMessage,
                aspectLabel = aspectLabel(resizeModeIndex),
                showExternalSubtitleControl = uiState.subtitleFeaturesEnabled,
                showAudioTrackControl = uiState.audioTracks.count { it.supported } > 1,
                onBack = leavePlayer,
                onAudioTrack = {
                    audioTrackDialogVisible = true
                    controlsVisible = true
                },
                onExternalSubtitle = {
                    if (!uiState.subtitleFeaturesEnabled) return@PlayerOverlay
                    viewModel.openExternalSubtitlePanel(durationMs)
                    controlsVisible = true
                },
                onPlaybackModePanel = {
                    playbackModePanelVisible = true
                    controlsVisible = true
                },
                onTogglePlay = {
                    viewModel.togglePlayPause()
                    controlsVisible = true
                },
                onSeekBack = {
                    viewModel.seekBack()
                    controlsVisible = true
                },
                onSeekForward = {
                    viewModel.seekForward()
                    controlsVisible = true
                },
                onSeek = { fraction ->
                    val target = (durationMs * fraction).toLong()
                    viewModel.seekTo(target)
                    positionMs = target
                    controlsVisible = true
                },
                onSpeedSelected = { selectedSpeed ->
                    speed = viewModel.selectPlaybackSpeed(selectedSpeed)
                    controlsVisible = true
                },
                onAspect = {
                    resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                    controlsVisible = true
                },
                onOrientation = {
                    isLandscape = !isLandscape
                    controlsVisible = true
                }
            )
        }

        ExternalSubtitlePanel(
            visible = uiState.subtitleFeaturesEnabled && uiState.externalSubtitlePanelVisible,
            queryNumber = uiState.externalSubtitleQueryNumber,
            provider = uiState.externalSubtitleProvider,
            providerOptions = uiState.subtitleSearchProviderOptions,
            localSubtitles = uiState.localSubtitles,
            onlineSubtitles = uiState.onlineSubtitles,
            externalSubtitleEnabled = uiState.externalSubtitleEnabled,
            activeSubtitleName = uiState.activeExternalSubtitleName,
            isSearching = uiState.externalSubtitleSearching,
            isDownloading = uiState.externalSubtitleDownloading,
            message = uiState.externalSubtitleMessage,
            error = uiState.externalSubtitleError,
            onDismiss = viewModel::dismissExternalSubtitlePanel,
            onProviderSelected = viewModel::selectExternalSubtitleProvider,
            onExternalSubtitleEnabledChange = viewModel::setExternalSubtitleEnabled,
            onSearchOnline = { viewModel.searchExternalSubtitles(durationMs) },
            onLocalSelected = viewModel::loadLocalSubtitle,
            onLocalDelete = viewModel::deleteLocalSubtitle,
            onOnlineSelected = viewModel::downloadAndLoadSubtitle
        )

        PlaybackModePanel(
            visible = playbackModePanelVisible,
            selectedMode = vrMode,
            selectedControlMode = vrControlMode,
            onDismiss = { playbackModePanelVisible = false },
            onVrMode = { mode ->
                viewModel.setVrMode(mode)
                controlsVisible = true
            },
            onVrControlMode = { mode ->
                viewModel.setVrControlMode(mode)
                controlsVisible = true
            }
        )

        AudioTrackDialog(
            visible = audioTrackDialogVisible,
            tracks = uiState.audioTracks,
            automatic = uiState.audioTrackAutomatic,
            onDismiss = { audioTrackDialogVisible = false },
            onAutomatic = { viewModel.selectAudioTrack(null, null) },
            onTrackSelected = { track ->
                viewModel.selectAudioTrack(track.groupIndex, track.trackIndex)
            }
        )

        AnimatedVisibility(
            visible = controlsVisible || controlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 18.dp, bottom = 18.dp)
        ) {
            PlayerLockButton(
                locked = controlsLocked,
                onClick = {
                    controlsLocked = !controlsLocked
                    controlsVisible = !controlsLocked
                }
            )
        }

    }
}

@Composable
private fun VrExternalSubtitleOverlay(
    player: Player,
    enabled: Boolean,
    style: ExternalSubtitleStyleSettings,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var subtitleText by remember(player) { mutableStateOf(player.currentCues.toSubtitleText()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                subtitleText = cueGroup.toSubtitleText()
            }
        }
        player.addListener(listener)
        subtitleText = player.currentCues.toSubtitleText()
        onDispose { player.removeListener(listener) }
    }

    if (!enabled || subtitleText.isBlank()) return

    val fontSizeSp = style.fontSizeSp.coerceIn(12, 64)
    val backgroundAlpha = style.backgroundAlphaPercent.coerceIn(0, 100) / 100f

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        val subtitleBottomPadding = maxHeight * (style.bottomPaddingPercent.coerceIn(0, 100) / 100f)
        val controlsBottomPadding = if (controlsVisible) 150.dp else 0.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = subtitleBottomPadding + controlsBottomPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = subtitleText,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.25f).sp,
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset.Zero,
                        blurRadius = 4f
                    )
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = backgroundAlpha))
                    .padding(
                        horizontal = if (backgroundAlpha > 0f) 8.dp else 0.dp,
                        vertical = if (backgroundAlpha > 0f) 3.dp else 0.dp
                    )
            )
        }
    }
}

private fun CueGroup.toSubtitleText(): String =
    cues
        .mapNotNull { cue ->
            cue.text
                ?.toString()
                ?.trim()
                ?.takeIf { text -> text.isNotBlank() }
        }
        .distinct()
        .joinToString("\n")

private enum class GestureSide {
    Brightness,
    Volume
}

private enum class GestureMode {
    Seek,
    VerticalAdjust
}

private data class GestureFeedback(
    val side: GestureSide,
    val percent: Int,
    val version: Long
)

@Composable
private fun GestureFeedbackOverlay(
    feedback: GestureFeedback?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val current = feedback ?: return@AnimatedVisibility
        val isVolumeMuted = current.side == GestureSide.Volume && current.percent == 0
        val icon = when {
            current.side == GestureSide.Brightness -> Icons.Rounded.Brightness6
            isVolumeMuted -> Icons.Rounded.VolumeOff
            else -> Icons.Rounded.VolumeUp
        }
        val label = when (current.side) {
            GestureSide.Brightness -> "亮度"
            GestureSide.Volume -> "音量"
        }
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = "$label ${current.percent}%",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(
                progress = { current.percent / 100f },
                modifier = Modifier
                    .width(156.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f)
            )
        }
    }
}

@Composable
private fun SeekDragFeedbackOverlay(
    targetPositionMs: Long?,
    startPositionMs: Long,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = targetPositionMs != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val target = targetPositionMs ?: return@AnimatedVisibility
        val deltaSeconds = ((target - startPositionMs) / 1000).toInt()
        val deltaText = when {
            deltaSeconds > 0 -> "+${formatSeekDelta(deltaSeconds)}"
            deltaSeconds < 0 -> "-${formatSeekDelta(-deltaSeconds)}"
            else -> "0:00"
        }
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.66f))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = deltaText,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = formatTime(target),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ManualControlOverlay(
    control: GestureSide?,
    brightnessValue: Float,
    volumeValue: Float,
    maxVolume: Int,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = control != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val current = control ?: return@AnimatedVisibility
        val isBrightness = current == GestureSide.Brightness
        val percent = if (isBrightness) {
            (brightnessValue * 100f).roundToInt().coerceIn(0, 100)
        } else {
            ((volumeValue / maxVolume.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        }
        val icon = when {
            isBrightness -> Icons.Rounded.Brightness6
            percent == 0 -> Icons.Rounded.VolumeOff
            else -> Icons.Rounded.VolumeUp
        }
        val label = if (isBrightness) "\u4EAE\u5EA6" else "\u97F3\u91CF"
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(onClick = {})
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
                Text(
                    text = "$label $percent%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = if (isBrightness) brightnessValue else volumeValue,
                onValueChange = {
                    if (isBrightness) {
                        onBrightnessChange(it)
                    } else {
                        onVolumeChange(it)
                    }
                },
                valueRange = if (isBrightness) 0.02f..1f else 0f..maxVolume.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                )
            )
            Text(
                text = "\u70B9\u51FB\u753B\u9762\u6216\u64AD\u653E\u5668\u63A7\u4EF6\u53EF\u7EE7\u7EED\u64CD\u4F5C",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "\u5173\u95ED",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ExternalSubtitlePanel(
    visible: Boolean,
    queryNumber: String,
    provider: SubtitleSearchProvider,
    providerOptions: List<SubtitleSearchProvider>,
    localSubtitles: List<LocalSubtitleFile>,
    onlineSubtitles: List<JavzimuSubtitleResult>,
    externalSubtitleEnabled: Boolean,
    activeSubtitleName: String?,
    isSearching: Boolean,
    isDownloading: Boolean,
    message: String?,
    error: String?,
    onDismiss: () -> Unit,
    onProviderSelected: (SubtitleSearchProvider) -> Unit,
    onExternalSubtitleEnabledChange: (Boolean) -> Unit,
    onSearchOnline: () -> Unit,
    onLocalSelected: (LocalSubtitleFile) -> Unit,
    onLocalDelete: (LocalSubtitleFile) -> Unit,
    onOnlineSelected: (JavzimuSubtitleResult) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
    ) {
        val panelWidth = when {
            maxWidth < 320.dp -> maxWidth
            maxWidth < 520.dp -> maxWidth - 88.dp
            else -> 300.dp
        }
        val quietClickSource = remember { MutableInteractionSource() }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.26f))
                    .clickable(
                        interactionSource = quietClickSource,
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xF21A1C20))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (queryNumber.isBlank()) "字幕" else "字幕 · $queryNumber",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = provider.label,
                            color = Color.White.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "外挂字幕",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = activeSubtitleName ?: "关闭",
                                color = Color.White.copy(alpha = 0.58f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = externalSubtitleEnabled,
                            onCheckedChange = onExternalSubtitleEnabledChange,
                            enabled = !isDownloading,
                            modifier = Modifier.scale(0.80f)
                        )
                    }

                    Text(
                        text = "字幕来源",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    providerOptions.forEach { option ->
                        val selected = option == provider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (selected) {
                                        Color.White.copy(alpha = 0.16f)
                                    } else {
                                        Color.White.copy(alpha = 0.07f)
                                    }
                                )
                                .clickable(
                                    enabled = !selected && !isSearching && !isDownloading,
                                    onClick = { onProviderSelected(option) }
                                )
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = when (option) {
                                        SubtitleSearchProvider.Cloud115 -> "搜索网盘里的字幕文件"
                                        SubtitleSearchProvider.Xunlei -> "按番号搜索迅雷字幕"
                                        else -> "在线字幕来源"
                                    },
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "当前",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onSearchOnline,
                        enabled = !isSearching && !isDownloading && queryNumber.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("搜索 ${provider.label}", style = MaterialTheme.typography.labelMedium)
                    }

                    if (isSearching || isDownloading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (isDownloading) "正在下载字幕..." else "正在搜索字幕...",
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                    }
                    error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                    Text(
                        text = "本地字幕",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (localSubtitles.isEmpty()) {
                        Text("当前目录没有可加载字幕", color = Color.White.copy(alpha = 0.52f), style = MaterialTheme.typography.labelSmall)
                    } else {
                        localSubtitles.forEach { subtitle ->
                            SubtitleOptionRow(
                                title = subtitle.name,
                                subtitle = if (subtitle.name == activeSubtitleName) "当前加载" else "点击加载",
                                enabled = !isDownloading,
                                onDelete = { onLocalDelete(subtitle) },
                                onClick = { onLocalSelected(subtitle) }
                            )
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                    Text(
                        text = "在线字幕",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isSearching && onlineSubtitles.isEmpty()) {
                        Text("没有可下载字幕", color = Color.White.copy(alpha = 0.52f), style = MaterialTheme.typography.labelSmall)
                    } else {
                        onlineSubtitles.forEach { result ->
                            SubtitleOptionRow(
                                title = result.name,
                                subtitle = result.displayName
                                    .removePrefix(result.name)
                                    .trim()
                                    .trimStart('·')
                                    .trim()
                                    .ifBlank { "${result.provider.label} · 点击下载并加载" },
                                enabled = !isDownloading,
                                onClick = { onOnlineSelected(result) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTrackDialog(
    visible: Boolean,
    tracks: List<AudioTrackOption>,
    automatic: Boolean,
    onDismiss: () -> Unit,
    onAutomatic: () -> Unit,
    onTrackSelected: (AudioTrackOption) -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF21A1C20),
        title = {
            Text(
                text = "音轨",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioTrackOptionRow(
                    title = "自动选择",
                    details = "由播放器自动选择合适的音轨",
                    selected = automatic,
                    enabled = true,
                    onClick = onAutomatic
                )
                tracks.forEachIndexed { index, track ->
                    AudioTrackOptionRow(
                        title = track.title.ifBlank { "音轨 ${index + 1}" },
                        details = track.details.ifBlank {
                            if (track.supported) "音轨 ${index + 1}" else "当前设备不支持"
                        },
                        selected = !automatic && track.selected,
                        enabled = track.supported,
                        onClick = { onTrackSelected(track) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
            }
        }
    )
}

@Composable
private fun AudioTrackOptionRow(
    title: String,
    details: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (selected) 0.16f else if (enabled) 0.08f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.42f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = details,
                color = Color.White.copy(alpha = if (enabled) 0.58f else 0.34f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Text(
                text = "当前",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SubtitleOptionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        onDelete?.let { delete ->
            IconButton(
                onClick = delete,
                enabled = enabled,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "删除字幕",
                    tint = Color.White.copy(alpha = if (enabled) 0.86f else 0.38f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun PlayerView.applyExternalSubtitleStyle(style: ExternalSubtitleStyleSettings) {
    val backgroundAlpha = (style.backgroundAlphaPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
    subtitleView?.setApplyEmbeddedStyles(false)
    subtitleView?.setApplyEmbeddedFontSizes(false)
    subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp.toFloat())
    subtitleView?.setBottomPaddingFraction(style.bottomPaddingPercent.coerceIn(0, 100) / 100f)
    subtitleView?.setStyle(
        CaptionStyleCompat(
            AndroidColor.WHITE,
            AndroidColor.argb(backgroundAlpha, 0, 0, 0),
            AndroidColor.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            AndroidColor.BLACK,
            Typeface.DEFAULT_BOLD
        )
    )
}

@Composable
private fun PlayerOverlay(
    title: String,
    isPlaying: Boolean,
    isLandscape: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    speeds: List<Float>,
    errorMessage: String?,
    aspectLabel: String,
    showExternalSubtitleControl: Boolean,
    showAudioTrackControl: Boolean,
    onBack: () -> Unit,
    onAudioTrack: () -> Unit,
    onExternalSubtitle: () -> Unit,
    onPlaybackModePanel: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onAspect: () -> Unit,
    onOrientation: () -> Unit
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var overlayRootOffset by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var speedButtonOffset by remember { mutableStateOf(IntOffset.Zero) }
    var speedButtonSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val dismissSpeedMenuInteraction = remember { MutableInteractionSource() }
    val menuWidthPx = with(density) { 92.dp.roundToPx() }
    val menuItemHeightPx = with(density) { 36.dp.roundToPx() }
    val menuPaddingPx = with(density) { 6.dp.roundToPx() }
    val menuGapPx = with(density) { 10.dp.roundToPx() }
    val menuMarginPx = with(density) { 12.dp.roundToPx() }
    val menuHeightPx = menuItemHeightPx * speeds.size + menuPaddingPx * 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayRootOffset = coordinates.positionInRoot()
                overlaySize = coordinates.size
            }
    ) {
        TopGradientBar(
            title = title,
            showExternalSubtitleControl = showExternalSubtitleControl,
            showAudioTrackControl = showAudioTrackControl,
            onBack = onBack,
            onAudioTrack = onAudioTrack,
            onExternalSubtitle = onExternalSubtitle,
            onModeMenuClick = {
                speedMenuExpanded = false
                onPlaybackModePanel()
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        BottomGradientControls(
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            aspectLabel = aspectLabel,
            errorMessage = errorMessage,
            isPlaying = isPlaying,
            isLandscape = isLandscape,
            onSeekBack = onSeekBack,
            onTogglePlay = onTogglePlay,
            onSeekForward = onSeekForward,
            onSeek = onSeek,
            onSpeedClick = {
                speedMenuExpanded = true
            },
            onSpeedButtonPositioned = { coordinates ->
                val position = coordinates.positionInRoot()
                speedButtonOffset = IntOffset(
                    x = (position.x - overlayRootOffset.x).roundToInt(),
                    y = (position.y - overlayRootOffset.y).roundToInt()
                )
                speedButtonSize = coordinates.size
            },
            onAspect = onAspect,
            onOrientation = onOrientation,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (speedMenuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(8f)
                    .clickable(
                        interactionSource = dismissSpeedMenuInteraction,
                        indication = null
                    ) {
                        speedMenuExpanded = false
                    }
            )
        }

        AnimatedVisibility(
            visible = speedMenuExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 5 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 5 }),
            modifier = Modifier
                .zIndex(9f)
                .offset {
                    val centeredX = speedButtonOffset.x + speedButtonSize.width / 2 - menuWidthPx / 2
                    val maxX = (overlaySize.width - menuWidthPx - menuMarginPx).coerceAtLeast(menuMarginPx)
                    val x = centeredX.coerceIn(menuMarginPx, maxX)
                    val preferredY = speedButtonOffset.y - menuHeightPx - menuGapPx
                    val maxY = (overlaySize.height - menuHeightPx - menuMarginPx).coerceAtLeast(menuMarginPx)
                    val fallbackY = speedButtonOffset.y + speedButtonSize.height + menuGapPx
                    val y = if (preferredY >= menuMarginPx) {
                        preferredY
                    } else {
                        fallbackY.coerceIn(menuMarginPx, maxY)
                    }
                    IntOffset(x, y)
                }
        ) {
            PlaybackSpeedInlineMenu(
                speeds = speeds,
                selectedSpeed = speed,
                onSpeedSelected = { selectedSpeed ->
                    onSpeedSelected(selectedSpeed)
                    speedMenuExpanded = false
                }
            )
        }

    }
}

@Composable
private fun TopGradientBar(
    title: String,
    showExternalSubtitleControl: Boolean,
    showAudioTrackControl: Boolean,
    onBack: () -> Unit,
    onAudioTrack: () -> Unit,
    onExternalSubtitle: () -> Unit,
    onModeMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        GlassIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
        if (showAudioTrackControl) {
            GlassIconButton(
                icon = Icons.Rounded.VolumeUp,
                contentDescription = "音轨",
                onClick = onAudioTrack
            )
        }
        if (showExternalSubtitleControl) {
            GlassIconButton(
                icon = Icons.Rounded.ClosedCaption,
                contentDescription = "字幕",
                onClick = onExternalSubtitle
            )
        }
        GlassIconButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = "\u64AD\u653E\u6A21\u5F0F",
            onClick = onModeMenuClick
        )
    }
}

@Composable
private fun PlaybackModePanel(
    visible: Boolean,
    selectedMode: VrMode,
    selectedControlMode: VrControlMode,
    onDismiss: () -> Unit,
    onVrMode: (VrMode) -> Unit,
    onVrControlMode: (VrControlMode) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(19f)
    ) {
        val quietClickSource = remember { MutableInteractionSource() }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clickable(
                        interactionSource = quietClickSource,
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PlaybackModeSidePanel(
                selectedMode = selectedMode,
                selectedControlMode = selectedControlMode,
                onDismiss = onDismiss,
                onVrMode = onVrMode,
                onVrControlMode = onVrControlMode
            )
        }
    }
}

@Composable
private fun PlaybackModeSidePanel(
    selectedMode: VrMode,
    selectedControlMode: VrControlMode,
    onDismiss: () -> Unit,
    onVrMode: (VrMode) -> Unit,
    onVrControlMode: (VrControlMode) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
        val panelWidth = when {
            maxWidth < 320.dp -> maxWidth
            maxWidth < 520.dp -> maxWidth - 88.dp
            else -> 300.dp
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .background(Color(0xF21A1C20))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放模式",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlaybackModeSectionTitle("画面")
                VrMode.entries.forEach { mode ->
                    PlaybackModeOptionRow(
                        title = mode.label,
                        subtitle = mode.modeSubtitle(),
                        selected = mode == selectedMode,
                        enabled = true,
                        onClick = { onVrMode(mode) }
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                PlaybackModeSectionTitle("控制方式")
                VrControlMode.entries.forEach { mode ->
                    PlaybackModeOptionRow(
                        title = mode.label,
                        subtitle = mode.controlSubtitle(),
                        selected = mode == selectedControlMode,
                        enabled = selectedMode.isVr,
                        onClick = { onVrControlMode(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackModeSectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.58f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
private fun PlaybackModeOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    selected -> Color.White.copy(alpha = 0.16f)
                    enabled -> Color.White.copy(alpha = 0.07f)
                    else -> Color.White.copy(alpha = 0.035f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = if (enabled) 0.96f else 0.36f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = if (enabled) 0.58f else 0.28f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Text(
                text = "\u2713",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun VrMode.modeSubtitle(): String =
    when (this) {
        VrMode.Normal2D -> "普通 2D 播放"
        VrMode.Vr360 -> "360 全景"
        VrMode.Vr180 -> "180 全景"
        VrMode.Vr360Sbs -> "360 左右"
        VrMode.Vr180Sbs -> "180 左右"
        VrMode.Vr360Ou -> "360 上下"
        VrMode.Vr180Ou -> "180 上下"
    }

private fun VrControlMode.controlSubtitle(): String =
    when (this) {
        VrControlMode.Touch -> "手指拖动画面"
        VrControlMode.Sensor -> "跟随陀螺仪"
        VrControlMode.TouchAndSensor -> "手指和陀螺仪同时启用"
    }

@Composable
private fun CenterControls(
    isPlaying: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton(
            icon = Icons.Rounded.Replay10,
            contentDescription = "Replay 10 seconds",
            size = 42,
            iconSize = 27,
            onClick = onSeekBack
        )
        RoundIconButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            size = 54,
            iconSize = 32,
            onClick = onTogglePlay
        )
        RoundIconButton(
            icon = Icons.Rounded.Forward10,
            contentDescription = "Forward 10 seconds",
            size = 42,
            iconSize = 27,
            onClick = onSeekForward
        )
    }
}

@Composable
private fun BottomGradientControls(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    aspectLabel: String,
    errorMessage: String?,
    isPlaying: Boolean,
    isLandscape: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    onSpeedButtonPositioned: (LayoutCoordinates) -> Unit,
    onAspect: () -> Unit,
    onOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        errorMessage?.let {
            Text(
                text = "Playback failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeText(formatTime(positionMs))
            Slider(
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                onValueChange = onSeek,
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            TimeText(formatTime(durationMs))
        }
        if (isLandscape) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp)
            ) {
                CenterControls(
                    isPlaying = isPlaying,
                    onSeekBack = onSeekBack,
                    onTogglePlay = onTogglePlay,
                    onSeekForward = onSeekForward,
                    modifier = Modifier.align(Alignment.Center)
                )
                PlayerToolRow(
                    speed = speed,
                    isLandscape = isLandscape,
                    onSpeedClick = onSpeedClick,
                    onSpeedButtonPositioned = onSpeedButtonPositioned,
                    onOrientation = onOrientation,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        } else {
            CenterControls(
                isPlaying = isPlaying,
                onSeekBack = onSeekBack,
                onTogglePlay = onTogglePlay,
                onSeekForward = onSeekForward,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 0.dp, bottom = 6.dp)
            )
            PlayerToolRow(
                speed = speed,
                isLandscape = isLandscape,
                onSpeedClick = onSpeedClick,
                onSpeedButtonPositioned = onSpeedButtonPositioned,
                onOrientation = onOrientation,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun PlaybackSpeedInlineMenu(
    speeds: List<Float>,
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        speeds.asReversed().forEach { option ->
            val selected = abs(option - selectedSpeed) < 0.001f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = if (selected) 0.18f else 0f))
                    .clickable { onSpeedSelected(option) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = option.formatSpeed() + "x",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (selected) "\u2713" else "",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PlayerToolRow(
    speed: Float,
    isLandscape: Boolean,
    onSpeedClick: () -> Unit,
    onSpeedButtonPositioned: (LayoutCoordinates) -> Unit,
    onOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassToolButton(
            icon = Icons.Rounded.Speed,
            label = "${speed.formatSpeed()}x",
            onClick = onSpeedClick,
            modifier = Modifier.onGloballyPositioned(onSpeedButtonPositioned)
        )
        GlassToolButton(
            icon = if (isLandscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            label = if (isLandscape) "\u7AD6\u5C4F" else "\u6A2A\u5C4F",
            onClick = onOrientation
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Int,
    iconSize: Int = 34,
    onClick: () -> Unit
) {
    IconButton(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f)),
        onClick = onClick
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    IconButton(
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f)),
        onClick = onClick
    ) {
        if (text != null) {
            Text(text = text, color = Color.White, style = MaterialTheme.typography.titleLarge)
        } else {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}

@Composable
private fun PlayerLockButton(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (locked) 0.62f else 0.38f))
    ) {
        Icon(
            imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = if (locked) "解除锁定" else "锁定控件",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun GlassToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimeText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.86f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium
    )
}

private fun aspectLabel(index: Int): String = when (index) {
    1 -> "Fill"
    2 -> "Zoom"
    else -> "Fit"
}

private fun Float.formatSpeed(): String =
    if (this % 1f == 0f) toInt().toString() else toString()

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatSeekDelta(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
