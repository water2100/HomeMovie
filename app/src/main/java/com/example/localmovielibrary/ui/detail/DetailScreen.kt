package com.example.localmovielibrary.ui.detail

import android.net.Uri
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.MoviePlaybackPart
import com.example.localmovielibrary.scraper.ActorAvatarStore
import com.example.localmovielibrary.scraper.MissavScraper
import com.example.localmovielibrary.ui.shared.UriImage
import com.example.localmovielibrary.ui.shared.artworkCacheRevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailBackground = Color(0xFF101010)
private val DetailPanel = Color(0xFF1B1B1B)
private val DetailPanelSoft = Color.White.copy(alpha = 0.075f)
private val DetailMuted = Color.White.copy(alpha = 0.62f)
private val EmbyGreen = Color(0xFF54B56B)

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlay: (videoUri: String, title: String, fileName: String) -> Unit,
    onFilterClick: (filterType: String, filterValue: String) -> Unit,
    onMovieClick: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenMissavCookie: (String) -> Unit
) {
    val movie by viewModel.movie.collectAsStateWithLifecycle()
    val isScraping by viewModel.isScraping.collectAsStateWithLifecycle()
    val similarMovies by viewModel.similarMovies.collectAsStateWithLifecycle()
    val playbackParts by viewModel.playbackParts.collectAsStateWithLifecycle()
    val hiddenMissavRequest by viewModel.hiddenMissavRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var cachedMovie by remember { mutableStateOf<MovieEntity?>(null) }
    var showPathDialog by rememberSaveable { mutableStateOf(false) }
    var showNfoDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showClearScrapeConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(movie) {
        movie?.let { cachedMovie = it }
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DetailEvent.Message -> {
                    val snackbarJob = launch { snackbarHostState.showSnackbar(event.text) }
                    delay(1_600)
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarJob.cancel()
                }
                is DetailEvent.OpenMovie -> onOpenMovie(event.movieId)
                is DetailEvent.OpenMissavCookie -> onOpenMissavCookie(event.number)
                DetailEvent.Deleted -> onBack()
            }
        }
    }

    Scaffold(
        containerColor = DetailBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(top = 72.dp, start = 14.dp, end = 14.dp)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            hiddenMissavRequest?.let { request ->
                HiddenMissavWebView(
                    request = request,
                    onHtmlReady = viewModel::onHiddenMissavHtmlReady,
                    onFailed = viewModel::onHiddenMissavFailed
                )
            }
            val displayMovie = movie ?: cachedMovie
            displayMovie?.let {
                MovieDetailScreen(
                    movie = it,
                    onBack = onBack,
                    playbackParts = playbackParts,
                    onPlay = { part -> onPlay(part.videoUri, it.title, part.fileName) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleWatched = viewModel::toggleWatched,
                    onShowPaths = {
                        showPathDialog = true
                        viewModel.menuFeedback("Showing file paths")
                    },
                    onShowNfo = {
                        showNfoDialog = true
                        viewModel.menuFeedback("Showing parsed NFO fields")
                    },
                    onRenameRequest = { showRenameDialog = true },
                    onRefresh = viewModel::refreshMovie,
                    onScrapeDefault = viewModel::scrapeWithDefault,
                    onScrapeDmm = viewModel::scrapeWithDmm,
                    onScrapeDmm2 = viewModel::scrapeWithDmm2,
                    onScrapeOfficial = viewModel::scrapeWithOfficial,
                    onScrapeJavbus = viewModel::scrapeWithJavbus,
                    onScrapeMissav = viewModel::scrapeWithMissav,
                    onRescrapeDefault = viewModel::rescrapeWithDefault,
                    onRescrapeDmm = viewModel::rescrapeWithDmm,
                    onRescrapeDmm2 = viewModel::rescrapeWithDmm2,
                    onRescrapeOfficial = viewModel::rescrapeWithOfficial,
                    onRescrapeJavbus = viewModel::rescrapeWithJavbus,
                    onRescrapeMissav = viewModel::rescrapeWithMissav,
                    onClearScrapeRequest = { showClearScrapeConfirm = true },
                    onDeleteRequest = { showDeleteConfirm = true },
                    onActorClick = { onFilterClick("actor", it) },
                    onTagClick = { onFilterClick("tag", it) },
                    onGenreClick = { onFilterClick("genre", it) },
                    similarMovies = similarMovies,
                    onSimilarClick = onMovieClick
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DetailBackground),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isScraping) "正在刷新影片信息..." else "影片信息暂不可用",
                    color = Color.White
                )
            }
        }
    }

    (movie ?: cachedMovie)?.let {
        if (showPathDialog) {
            FilePathsDialog(movie = it, onDismiss = { showPathDialog = false })
        }
        if (showNfoDialog) {
            ParsedNfoDialog(movie = it, onDismiss = { showNfoDialog = false })
        }
        if (showRenameDialog) {
            RenameMovieFileDialog(
                movie = it,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newFileName ->
                    showRenameDialog = false
                    viewModel.renameMovieFile(newFileName)
                }
            )
        }
        if (showDeleteConfirm) {
            ConfirmDeleteDialog(
                movieTitle = it.title,
                onDismiss = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    viewModel.deleteMovie()
                }
            )
        }
        if (showClearScrapeConfirm) {
            ConfirmClearScrapeDialog(
                movieTitle = it.title,
                onDismiss = { showClearScrapeConfirm = false },
                onConfirm = {
                    showClearScrapeConfirm = false
                    viewModel.clearScrapeFiles()
                }
            )
        }
    }
}

@Composable
fun MovieDetailScreen(
    movie: MovieEntity,
    onBack: () -> Unit,
    playbackParts: List<MoviePlaybackPart>,
    onPlay: (MoviePlaybackPart) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onShowPaths: () -> Unit,
    onShowNfo: () -> Unit,
    onRenameRequest: () -> Unit,
    onRefresh: () -> Unit,
    onScrapeDefault: () -> Unit,
    onScrapeDmm: () -> Unit,
    onScrapeDmm2: () -> Unit,
    onScrapeOfficial: () -> Unit,
    onScrapeJavbus: () -> Unit,
    onScrapeMissav: () -> Unit,
    onRescrapeDefault: () -> Unit,
    onRescrapeDmm: () -> Unit,
    onRescrapeDmm2: () -> Unit,
    onRescrapeOfficial: () -> Unit,
    onRescrapeJavbus: () -> Unit,
    onRescrapeMissav: () -> Unit,
    onClearScrapeRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    onActorClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    similarMovies: List<MovieEntity>,
    onSimilarClick: (Long) -> Unit
) {
    val scrapeFailureReason = movie.scrapeFailureReason?.trim()?.takeIf { it.isNotBlank() }
    var showScrapeFailureDialog by rememberSaveable(movie.id, scrapeFailureReason) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailBackground)
    ) {
        IconButton(
            modifier = Modifier
                .padding(start = 12.dp, top = 12.dp)
                .size(48.dp)
                .zIndex(3f)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f)),
            onClick = onBack
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MobileHeroImage(movie)
            Column(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MobileTitleBlock(movie = movie, onGenreClick = onGenreClick)
                scrapeFailureReason?.let { reason ->
                    ScrapeFailureNotice(
                        reason = reason,
                        onClick = { showScrapeFailureDialog = true }
                    )
                }
                MobileMainButtons(playbackParts = playbackParts, onPlay = onPlay, onTrailer = onShowNfo)
                MobileActionBar(
                    movie = movie,
                    onDownload = onShowPaths,
                    onToggleWatched = onToggleWatched,
                    onToggleFavorite = onToggleFavorite,
                    onRenameRequest = onRenameRequest,
                    onDeleteRequest = onDeleteRequest,
                    onShowNfo = onShowNfo,
                    onRefresh = onRefresh,
                    onScrapeDefault = onScrapeDefault,
                    onScrapeDmm = onScrapeDmm,
                    onScrapeDmm2 = onScrapeDmm2,
                    onScrapeOfficial = onScrapeOfficial,
                    onScrapeJavbus = onScrapeJavbus,
                    onScrapeMissav = onScrapeMissav,
                    onRescrapeDefault = onRescrapeDefault,
                    onRescrapeDmm = onRescrapeDmm,
                    onRescrapeDmm2 = onRescrapeDmm2,
                    onRescrapeOfficial = onRescrapeOfficial,
                    onRescrapeJavbus = onRescrapeJavbus,
                    onRescrapeMissav = onRescrapeMissav,
                    onClearScrapeRequest = onClearScrapeRequest
                )
                ReleaseAndOverview(movie = movie, onTagClick = onTagClick)
                CastSection(actors = movie.actors, onActorClick = onActorClick)
                CollectionSection(movie)
                SimilarSection(movies = similarMovies, onMovieClick = onSimilarClick)
                OtherInfoSection(movie = movie, onTagClick = onTagClick)
            }
        }

        if (showScrapeFailureDialog && scrapeFailureReason != null) {
            AlertDialog(
                onDismissRequest = { showScrapeFailureDialog = false },
                title = { Text("未刮削成功") },
                text = { Text(scrapeFailureReason) },
                confirmButton = {
                    TextButton(onClick = { showScrapeFailureDialog = false }) {
                        Text("知道了")
                    }
                }
            )
        }
    }
}

@Composable
private fun ScrapeFailureNotice(reason: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF4D5E).copy(alpha = 0.20f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFFF7A86),
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "未刮削成功，点击查看原因",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = reason,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MobileHeroImage(movie: MovieEntity) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(328.dp)
            .background(Color.Black)
    ) {
        val backdropUri = movie.fanartUri ?: movie.posterUri ?: movie.thumbUri
        UriImage(
            uri = backdropUri,
            modifier = Modifier
                .fillMaxSize()
                .then(if (movie.fanartUri == null) Modifier.blur(8.dp) else Modifier),
            contentScale = ContentScale.Crop,
            cacheKey = movie.artworkCacheRevision()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.10f),
                            0.55f to Color.Transparent,
                            1.00f to DetailBackground
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileTitleBlock(movie: MovieEntity, onGenreClick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = movie.title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 30.sp
        )
        movie.originalTitle?.takeIf { it.isNotBlank() && it != movie.title }?.let {
            Text(
                text = it,
                color = DetailMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MetadataLine(movie)
        if (movie.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                movie.genres.take(4).forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.84f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable { onGenreClick(genre) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(movie: MovieEntity) {
    val values = listOfNotNull(
        movie.rating?.let { "★ %.1f".format(it) },
        movie.year?.toString(),
        movie.mpaa?.takeIf { it.isNotBlank() },
        movie.runtimeMinutes?.let { "${it}分钟" }
    )
    if (values.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
            Text(
                text = value,
                color = if (index == 0) Color(0xFFFF5A6A) else DetailMuted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
            )
            if (index < values.lastIndex) {
                Text("•", color = DetailMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MobileMainButtons(
    playbackParts: List<MoviePlaybackPart>,
    onPlay: (MoviePlaybackPart) -> Unit,
    onTrailer: () -> Unit
) {
    var partMenuExpanded by remember { mutableStateOf(false) }
    val defaultPart = playbackParts.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f)) {
            Button(
                onClick = {
                    if (playbackParts.size > 1) {
                        partMenuExpanded = true
                    } else {
                        defaultPart?.let(onPlay)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (playbackParts.size > 1) "播放 ${defaultPart?.label ?: "A"}" else "播放", fontWeight = FontWeight.Bold)
            }
            DropdownMenu(
                expanded = partMenuExpanded,
                onDismissRequest = { partMenuExpanded = false },
                modifier = Modifier.background(DetailPanel)
            ) {
                playbackParts.forEach { part ->
                    DropdownMenuItem(
                        text = { Text("播放 ${part.label}", color = Color.White) },
                        onClick = {
                            partMenuExpanded = false
                            onPlay(part)
                        }
                    )
                }
            }
        }
        Button(
            onClick = onTrailer,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Rounded.SmartDisplay, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("预告片", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MobileActionBar(
    movie: MovieEntity,
    onDownload: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRenameRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    onShowNfo: () -> Unit,
    onRefresh: () -> Unit,
    onScrapeDefault: () -> Unit,
    onScrapeDmm: () -> Unit,
    onScrapeDmm2: () -> Unit,
    onScrapeOfficial: () -> Unit,
    onScrapeJavbus: () -> Unit,
    onScrapeMissav: () -> Unit,
    onRescrapeDefault: () -> Unit,
    onRescrapeDmm: () -> Unit,
    onRescrapeDmm2: () -> Unit,
    onRescrapeOfficial: () -> Unit,
    onRescrapeJavbus: () -> Unit,
    onRescrapeMissav: () -> Unit,
    onClearScrapeRequest: () -> Unit
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val canScrape = movie.videoName.endsWith(".strm", ignoreCase = true) && movie.nfoUri == null
    val canRescrape = movie.videoName.endsWith(".strm", ignoreCase = true) && movie.nfoUri != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailActionItem(Icons.Rounded.Download, "下载", onDownload)
        DetailActionItem(
            if (movie.isWatched) Icons.Rounded.CheckCircle else Icons.Rounded.Check,
            if (movie.isWatched) "已播放" else "已看过",
            onToggleWatched,
            active = movie.isWatched
        )
        DetailActionItem(
            if (movie.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (movie.isFavorite) "已收藏" else "收藏",
            onToggleFavorite,
            active = movie.isFavorite
        )
        DetailActionItem(Icons.Rounded.Edit, "重命名", onRenameRequest)
        DetailActionItem(Icons.Rounded.DeleteOutline, "删除", onDeleteRequest)
        Box {
            DetailActionItem(Icons.Rounded.MoreHoriz, "更多", { moreExpanded = true })
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("查看 NFO 信息") },
                    onClick = {
                        moreExpanded = false
                        onShowNfo()
                    }
                )
                DropdownMenuItem(
                    text = { Text("刷新此影片") },
                    onClick = {
                        moreExpanded = false
                        onRefresh()
                    }
                )
                if (canScrape) {
                    DropdownMenuItem(
                        text = { Text("从优先级刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeDefault()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从 DMM 刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeDmm()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从 DMM2 刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeDmm2()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从 Official 刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeOfficial()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从 JavBus 刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeJavbus()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从 MissAV 刮削") },
                        onClick = {
                            moreExpanded = false
                            onScrapeMissav()
                        }
                    )
                }
                if (canRescrape) {
                    DropdownMenuItem(
                        text = { Text("用优先级重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeDefault()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("用 DMM 重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeDmm()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("用 DMM2 重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeDmm2()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("用 Official 重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeOfficial()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("用 JavBus 重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeJavbus()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("用 MissAV 重新刮削") },
                        onClick = {
                            moreExpanded = false
                            onRescrapeMissav()
                        }
                    )
                }
                if (movie.nfoUri != null || movie.posterUri != null || movie.fanartUri != null || movie.thumbUri != null) {
                    DropdownMenuItem(
                        text = { Text("清除刮削内容并还原") },
                        onClick = {
                            moreExpanded = false
                            onClearScrapeRequest()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Column(
        modifier = Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) EmbyGreen else Color.White,
            modifier = Modifier.size(25.dp)
        )
        Text(label, color = DetailMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ReleaseAndOverview(movie: MovieEntity, onTagClick: (String) -> Unit) {
    val overview = movie.plot?.takeIf { it.isNotBlank() }
        ?: movie.outline?.takeIf { it.isNotBlank() }
        ?: "暂无简介。"
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        movie.premiered?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "发行日期 $it",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = overview,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis
        )
        if (overview.length > 120) {
            Text(
                text = if (expanded) "收起" else "更多",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
        if (movie.tags.isNotEmpty()) {
            ChipFlow(values = movie.tags.take(8), onClick = onTagClick)
        }
    }
}

@Composable
fun CastSection(actors: List<String>, onActorClick: (String) -> Unit) {
    if (actors.isEmpty()) return
    val context = LocalContext.current
    val avatarStore = remember(context) { ActorAvatarStore(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSectionTitle("演职人员")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actors.take(24).forEach { actor ->
                CastCard(
                    name = actor,
                    avatarUri = avatarStore.avatarUri(actor),
                    onClick = { onActorClick(actor) }
                )
            }
        }
    }
}

@Composable
private fun CastCard(name: String, avatarUri: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(82.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DetailPanel),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUri != null) {
                UriImage(
                    uri = avatarUri,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    maxDecodeSize = 320
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Text(
            text = name,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "演员",
            color = DetailMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun CollectionSection(movie: MovieEntity) {
    val collectionLabel = movie.series?.takeIf { it.isNotBlank() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSectionTitle("所属合集")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallPosterCard(
                imageUri = movie.posterUri ?: movie.thumbUri,
                title = collectionLabel,
                subtitle = movie.title,
                cacheKey = movie.artworkCacheRevision()
            )
        }
    }
}

@Composable
private fun SimilarSection(movies: List<MovieEntity>, onMovieClick: (Long) -> Unit) {
    if (movies.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSectionTitle("其他类似")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            movies.forEach { movie ->
                SmallPosterCard(
                    imageUri = movie.posterUri ?: movie.thumbUri,
                    title = movie.title,
                    subtitle = movie.year?.toString().orEmpty(),
                    cacheKey = movie.artworkCacheRevision(),
                    onClick = { onMovieClick(movie.id) }
                )
            }
        }
    }
}

@Composable
private fun SmallPosterCard(
    imageUri: String?,
    title: String,
    subtitle: String,
    cacheKey: Any? = null,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(5.dp))
                .background(DetailPanel),
            contentAlignment = Alignment.Center
        ) {
            UriImage(
                uri = imageUri,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                maxDecodeSize = 520,
                cacheKey = cacheKey
            )
            if (imageUri == null) {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.76f))
            }
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = DetailMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherInfoSection(
    movie: MovieEntity,
    onTagClick: (String) -> Unit
) {
    val tagValues = (movie.tags + movie.genres).distinctByNormalizedText()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSectionTitle("其他信息")
        if (tagValues.isNotEmpty()) {
            Text("标签", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            ChipFlow(values = tagValues, onClick = onTagClick)
        }
        InfoLine("导演", movie.directors.joinToString(", "))
        InfoLine("媒体信息", listOfNotNull(movie.videoName, movie.runtimeMinutes?.let { "${it}分钟" }).joinToString(" / "))
        InfoLine("系列", movie.series.orEmpty())
        InfoLine("工作室", movie.studios.joinToString(", "))
        InfoLine("路径信息", readableFolderPath(movie.videoUri))
        InfoLine("添加时间", formatAddedTime(movie.scannedAtMillis))
    }
}

private fun List<String>.distinctByNormalizedText(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = DetailMuted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )
    }
}

private fun readableFolderPath(uriString: String): String {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
    if (uri.scheme == "file") {
        return uri.path?.substringBeforeLast('/', missingDelimiterValue = uri.path.orEmpty()).orEmpty()
    }

    val documentId = uri.pathSegments
        .indexOf("document")
        .takeIf { it >= 0 && it + 1 < uri.pathSegments.size }
        ?.let { uri.pathSegments[it + 1] }
    val treeId = uri.pathSegments
        .indexOf("tree")
        .takeIf { it >= 0 && it + 1 < uri.pathSegments.size }
        ?.let { uri.pathSegments[it + 1] }
    val storageId = documentId ?: treeId ?: return uriString
    val decoded = Uri.decode(storageId)
    val volume = decoded.substringBefore(':', "")
    val relativePath = decoded.substringAfter(':', "")
        .substringBeforeLast('/', missingDelimiterValue = decoded.substringAfter(':', ""))

    return when {
        volume.equals("primary", ignoreCase = true) && relativePath.isNotBlank() -> "/storage/emulated/0/$relativePath"
        volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
        relativePath.isNotBlank() -> "$volume:/$relativePath"
        volume.isNotBlank() -> "$volume:/"
        else -> uriString
    }
}

private fun formatAddedTime(scannedAtMillis: Long): String {
    if (scannedAtMillis <= 0) return ""
    return SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(scannedAtMillis))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(values: List<String>, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.filter { it.isNotBlank() }.forEach { value ->
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onClick(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun DetailSectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HiddenMissavWebView(
    request: HiddenMissavRequest,
    onHtmlReady: (requestId: Long, html: String, cookie: String) -> Unit,
    onFailed: (requestId: Long, message: String) -> Unit
) {
    var completed by remember(request.id) { mutableStateOf(false) }

    LaunchedEffect(request.id) {
        delay(25_000)
        if (!completed) {
            completed = true
            onFailed(request.id, "MissAV WebView 抓取超时，可能需要可见页面手动验证")
        }
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .zIndex(-1f),
        factory = { context ->
            WebView(context).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = MissavScraper.USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (completed || view == null) return
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                            if (completed) return@evaluateJavascript
                            val html = runCatching { JSONArray("[$encodedHtml]").getString(0) }.getOrDefault("")
                            when {
                                html.isBlank() -> Unit
                                html.isCloudflareChallengeHtml() -> Unit
                                html.isMissavUsableHtml(request.number) -> {
                                    completed = true
                                    onHtmlReady(request.id, html, collectMissavCookies())
                                }
                            }
                        }
                    }
                }
                loadUrl(request.url)
            }
        },
        update = { webView ->
            if (!completed && webView.url != request.url) {
                webView.loadUrl(request.url)
            }
        }
    )
}

private fun collectMissavCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie("https://missav.ai").orEmpty(),
        CookieManager.getInstance().getCookie("https://missav.ai/ja").orEmpty(),
        CookieManager.getInstance().getCookie("https://missav.ai/cn").orEmpty(),
        CookieManager.getInstance().getCookie("https://www.missav.ai").orEmpty()
    )
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}

private fun String.isCloudflareChallengeHtml(): Boolean {
    val lower = lowercase()
    return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower)
}

private fun String.isMissavUsableHtml(number: String): Boolean {
    if (isCloudflareChallengeHtml()) return false
    val lower = lowercase()
    val normalized = number.lowercase().replace("_", "-")
    val compact = normalized.replace("-", "")
    return "missav" in lower &&
        (normalized in lower || compact in lower || "og:title" in lower || "space-y-2" in lower || "text-nord6" in lower)
}

@Composable
private fun FilePathsDialog(movie: MovieEntity, onDismiss: () -> Unit) {
    DetailInfoDialog(title = "文件路径", onDismiss = onDismiss) {
        DialogLine("视频", movie.videoUri)
        DialogLine("NFO", movie.nfoUri ?: "未找到")
        DialogLine("海报", movie.posterUri ?: "未找到")
        DialogLine("背景图", movie.fanartUri ?: "未找到")
        DialogLine("缩略图", movie.thumbUri ?: "未找到")
    }
}

@Composable
private fun ParsedNfoDialog(movie: MovieEntity, onDismiss: () -> Unit) {
    val emptyText = "未提供"
    DetailInfoDialog(title = "NFO 解析信息", onDismiss = onDismiss) {
        DialogLine("标题", movie.title)
        DialogLine("原标题", movie.originalTitle ?: emptyText)
        DialogLine("年份", movie.year?.toString() ?: emptyText)
        DialogLine("片长", movie.runtimeMinutes?.let { "$it 分钟" } ?: emptyText)
        DialogLine("评分", movie.rating?.let { "%.1f".format(it) } ?: emptyText)
        DialogLine("分级", movie.mpaa ?: emptyText)
        DialogLine("发行日期", movie.premiered ?: emptyText)
        DialogLine("简介", movie.plot ?: movie.outline ?: emptyText)
        DialogLine("导演", movie.directors.joinToString(", ").ifBlank { emptyText })
        DialogLine("演员", movie.actors.joinToString(", ").ifBlank { emptyText })
        DialogLine("类型", movie.genres.joinToString(", ").ifBlank { emptyText })
        DialogLine("标签", movie.tags.joinToString(", ").ifBlank { emptyText })
        DialogLine("唯一标识", movie.uniqueIds.joinToString(", ").ifBlank { emptyText })
    }
}

@Composable
private fun RenameMovieFileDialog(
    movie: MovieEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by rememberSaveable(movie.id, movie.videoName) { mutableStateOf(movie.videoName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名 STRM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    placeholder = { Text("例如 MEYD-772.strm") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "只会重命名影片库里的 STRM 文件，不会删除 115 网盘里的真实视频。未填写 .strm 时会自动补上。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.trim().isNotBlank(),
                onClick = { onConfirm(draft) }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConfirmDeleteDialog(
    movieTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本地记录？") },
        text = {
            Text(
                "会删除 \"$movieTitle\" 的本地 STRM 影片目录、NFO 和图片，并同步清除网盘已添加状态。不会删除 115 网盘里的真实视频文件。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConfirmClearScrapeDialog(
    movieTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清除刮削内容？") },
        text = {
            Text(
                "会删除 \"$movieTitle\" 的 NFO、海报、背景图，并尝试把 STRM 还原到上级目录。不会删除真实视频文件。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认清除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DetailInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DialogLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

