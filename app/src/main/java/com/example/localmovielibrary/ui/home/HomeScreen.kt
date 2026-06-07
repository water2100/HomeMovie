package com.example.localmovielibrary.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.localmovielibrary.data.local.DomesticVideoSourceEntity
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.DomesticMovieWithSources
import com.example.localmovielibrary.data.repository.MovieMetadataSummary
import com.example.localmovielibrary.playback.USER_AGENT
import com.example.localmovielibrary.scraper.ActorAvatarStore
import com.example.localmovielibrary.ui.shared.MovieArtwork
import com.example.localmovielibrary.ui.shared.UriImage
import com.example.localmovielibrary.util.metadataKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

private val MoviesBackground = Color(0xFF070A0E)
private val MoviesSurface = Color(0xFF111720)
private val MoviesPanel = Color.White.copy(alpha = 0.075f)
private val MoviesAccent = Color(0xFF36C5F0)
private const val ALL_MOVIES_INITIAL_COUNT = 90
private const val ALL_MOVIES_PAGE_SIZE = 60
private const val ALL_MOVIES_PREFETCH_THRESHOLD = 18

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onOpenLibrary: () -> Unit
) {
    MoviesScreen(viewModel = viewModel, onMovieClick = onMovieClick, onPlay = onPlay, onOpenLibrary = onOpenLibrary)
}

@Composable
fun MoviesScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var lastScrollResetKey by rememberSaveable {
        mutableStateOf("${uiState.sortState.option.name}:${uiState.sortState.direction.name}:${uiState.imageMode.name}")
    }

    LaunchedEffect(uiState.sortState, uiState.imageMode) {
        val nextKey = "${uiState.sortState.option.name}:${uiState.sortState.direction.name}:${uiState.imageMode.name}"
        if (lastScrollResetKey != nextKey) {
            lastScrollResetKey = nextKey
            gridState.scrollToItem(0)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (uiState.imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MoviesBackground),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item(
            key = "movies-top-bar",
            contentType = "movies-top-bar",
            span = { GridItemSpan(maxLineSpan) }
        ) {
            MoviesTopBar(
                stats = uiState.stats,
                scanState = uiState.scanState
            )
        }

        if (uiState.movies.isEmpty()) {
            item(
                key = "empty-library",
                contentType = "empty-library",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                EmptyLibraryState(
                    isScanning = uiState.scanState is ScanState.Scanning
                )
            }
        } else {
            item(
                key = "movies-top-spacer",
                contentType = "section-spacer",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Spacer(Modifier.height(8.dp))
            }
            if (uiState.hasPendingMovieUpdates) {
                item(
                    key = "pending-movies-banner",
                    contentType = "pending-movies-banner",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    PendingMoviesBanner(
                        count = uiState.pendingNewCount,
                        onClick = viewModel::applyPendingMovieUpdates
                    )
                }
            }
            item(
                key = "recently-added-section",
                contentType = "movie-rail-section",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                HomeMovieSection(title = "\u6700\u8FD1\u6DFB\u52A0") {
                HorizontalMovieRail(
                    movies = uiState.recentlyAdded,
                    imageMode = uiState.imageMode,
                        onMovieClick = onMovieClick,
                        onPlay = onPlay,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onToggleWatched = viewModel::toggleWatched
                    )
                }
            }

            if (uiState.recentlyPlayed.isNotEmpty()) {
                item(
                    key = "recently-played-section",
                    contentType = "movie-rail-section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    HomeMovieSection(title = "最近播放") {
                        HorizontalMovieRail(
                            movies = uiState.recentlyPlayed,
                            imageMode = uiState.imageMode,
                            onMovieClick = onMovieClick,
                            onPlay = onPlay,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onToggleWatched = viewModel::toggleWatched
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MoviesLibraryScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onDomesticPlay: (DomesticMovieWithSources, DomesticVideoSourceEntity) -> Unit,
    onFilterClick: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabName by rememberSaveable { mutableStateOf(LibraryTab.All.name) }
    val availableTabs = remember(uiState.domesticPageEnabled) {
        LibraryTab.entries.filter { tab -> uiState.domesticPageEnabled || tab != LibraryTab.Domestic }
    }
    val selectedTab = availableTabs.firstOrNull { it.name == selectedTabName } ?: LibraryTab.All

    LaunchedEffect(Unit) {
        viewModel.refreshDomesticPageEnabled()
    }

    LaunchedEffect(selectedTabName, uiState.domesticPageEnabled) {
        if (selectedTabName != selectedTab.name) {
            selectedTabName = selectedTab.name
        }
    }

    LaunchedEffect(selectedTab, uiState.movies.size) {
        if (selectedTab != LibraryTab.All && selectedTab != LibraryTab.Domestic && selectedTab != LibraryTab.Years) {
            viewModel.refreshLibrarySummaries()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoviesBackground)
    ) {
        LibraryTopBar(
            selectedTab = selectedTab,
            tabs = availableTabs,
            stats = uiState.stats,
            sortState = uiState.sortState,
            imageMode = uiState.imageMode,
            onTabSelected = { selectedTabName = it.name },
            onSortSelected = viewModel::setSortOption,
            onToggleSortDirection = viewModel::toggleSortDirection,
            onImageModeSelected = viewModel::setImageMode
        )
        if (uiState.hasPendingMovieUpdates) {
            PendingMoviesBanner(
                count = uiState.pendingNewCount,
                onClick = viewModel::applyPendingMovieUpdates
            )
        }
        when (selectedTab) {
            LibraryTab.All -> LibraryAllMoviesGrid(
                movies = uiState.movies,
                imageMode = uiState.imageMode,
                sortState = uiState.sortState,
                onMovieClick = onMovieClick,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched
            )
            LibraryTab.Domestic -> DomesticMoviesGrid(
                movies = uiState.domesticMovies,
                onPlay = onDomesticPlay
            )
            LibraryTab.Collections -> LibrarySummaryGrid(
                title = "合集",
                summaries = uiState.librarySummaries.collections,
                emptyText = "还没有可归类的合集",
                onClick = { onFilterClick("collection", it.value) }
            )
            LibraryTab.Actors -> ActorSummaryGrid(
                summaries = uiState.librarySummaries.actors,
                refreshVersion = uiState.actorAvatarRefreshVersion,
                onClick = { onFilterClick("actor", it.value) }
            )
            LibraryTab.Tags -> LibrarySummaryGrid(
                title = "标签",
                summaries = uiState.librarySummaries.tags,
                emptyText = "还没有标签",
                onClick = { onFilterClick("tag", it.value) }
            )
            LibraryTab.Genres -> LibrarySummaryGrid(
                title = "类型",
                summaries = uiState.librarySummaries.genres,
                emptyText = "还没有类型",
                onClick = { onFilterClick("genre", it.value) }
            )
            LibraryTab.Years -> LibrarySummaryGrid(
                title = "年份",
                summaries = uiState.movies.mapNotNull { it.year?.toString() }.summaryValues().sortedByDescending { it.value },
                emptyText = "还没有年份信息",
                onClick = { onFilterClick("year", it.value) }
            )
            LibraryTab.Studios -> LibrarySummaryGrid(
                title = "工作室",
                summaries = uiState.librarySummaries.studios,
                emptyText = "还没有工作室信息",
                onClick = { onFilterClick("studio", it.value) }
            )
        }
    }
}

@Composable
fun MoviesTopBar(
    stats: LibraryStats,
    scanState: ScanState
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var imageModeExpanded by remember { mutableStateOf(false) }
    val imageMode = HomeImageMode.Poster
    val sortState = HomeSortState()
    val onImageModeSelected: (HomeImageMode) -> Unit = {}
    val onSortSelected: (HomeSortOption) -> Unit = {}
    val onToggleSortDirection: () -> Unit = {}

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), MoviesBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\u9996\u9875",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = scanMessage(scanState),
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (false) {
            Box {
                IconButton(onClick = { imageModeExpanded = true }) {
                    Text(
                        text = if (imageMode == HomeImageMode.Poster) "海报" else "缩略",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(expanded = imageModeExpanded, onDismissRequest = { imageModeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("使用 poster 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Poster)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("使用 thumb 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Thumb)
                        }
                    )
                }
            }

            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "排序", tint = Color.White)
                }
                if (sortExpanded) {
                    SortDialog(
                        sortState = sortState,
                        onDismiss = { sortExpanded = false },
                        onSortSelected = onSortSelected,
                        onToggleSortDirection = onToggleSortDirection
                    )
                }
            }
            }
        }
    }
}

@Composable
fun LibraryStatsRow(stats: LibraryStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatPill("\u5F71\u7247", stats.total.toString())
        StatPill("\u6536\u85CF", stats.favorites.toString())
        StatPill("\u5DF2\u89C2\u770B", stats.watched.toString())
    }
}

@Composable
private fun SortDialog(
    sortState: HomeSortState,
    onDismiss: () -> Unit,
    onSortSelected: (HomeSortOption) -> Unit,
    onToggleSortDirection: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .heightIn(max = 620.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF242426))
                    .padding(top = 18.dp, bottom = 10.dp)
            ) {
                Text(
                    text = "\u6392\u5E8F:",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn {
                    items(
                        count = HomeSortOption.values().size,
                        contentType = { "sort-option" }
                    ) { index ->
                        val option = HomeSortOption.values()[index]
                        SortDialogRow(
                            option = option,
                            selected = sortState.option == option,
                            direction = sortState.direction,
                            onClick = {
                                if (sortState.option == option) {
                                    onToggleSortDirection()
                                } else {
                                    onSortSelected(option)
                                }
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortDialogRow(
    option: HomeSortOption,
    selected: Boolean,
    direction: HomeSortDirection,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
        }
        Text(
            text = sortLabel(option),
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = if (direction == HomeSortDirection.Descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(MoviesPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LibraryEntryCard(
    total: Int,
    actorCount: Int,
    tagCount: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF182232), Color(0xFF111720))
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "全部影片",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "按全部、合集、演员、标签、类型继续浏览",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "进入",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill("影片", total.toString())
            StatPill("演员", actorCount.toString())
            StatPill("标签", tagCount.toString())
        }
    }
}

@Composable
private fun PendingMoviesBanner(
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF163247))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "\u6709 ${count} \u90E8\u65B0\u5F71\u7247",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "\u70B9\u51FB\u540E\u4E00\u6B21\u6027\u5237\u65B0\u5F71\u7247\u9875\u9762",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = "\u5237\u65B0",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun HomeMovieSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeSectionHeader(title = title, subtitle = subtitle)
        content()
    }
}

@Composable
private fun LibraryTopBar(
    selectedTab: LibraryTab,
    tabs: List<LibraryTab>,
    stats: LibraryStats,
    sortState: HomeSortState,
    imageMode: HomeImageMode,
    onTabSelected: (LibraryTab) -> Unit,
    onSortSelected: (HomeSortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onImageModeSelected: (HomeImageMode) -> Unit
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var imageModeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), MoviesBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "影片库",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${stats.total} 部影片",
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = { imageModeExpanded = true }) {
                    Text(
                        text = if (imageMode == HomeImageMode.Poster) "海报" else "缩略",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(expanded = imageModeExpanded, onDismissRequest = { imageModeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("使用 poster 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Poster)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("使用 thumb 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Thumb)
                        }
                    )
                }
            }
            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "排序", tint = Color.White)
                }
                if (sortExpanded) {
                    SortDialog(
                        sortState = sortState,
                        onDismiss = { sortExpanded = false },
                        onSortSelected = onSortSelected,
                        onToggleSortDirection = onToggleSortDirection
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(
                items = tabs,
                key = { it.name },
                contentType = { "library-tab" }
            ) { tab ->
                val selected = selectedTab == tab
                Text(
                    text = tab.label,
                    color = if (selected) Color.Black else Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.10f))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 15.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryAllMoviesGrid(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    sortState: HomeSortState,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onToggleFavorite: (MovieEntity) -> Unit,
    onToggleWatched: (MovieEntity) -> Unit
) {
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var visibleCount by rememberSaveable { mutableStateOf(ALL_MOVIES_INITIAL_COUNT) }
    var lastScrollResetKey by rememberSaveable {
        mutableStateOf("${sortState.option.name}:${sortState.direction.name}:${imageMode.name}")
    }
    val visibleMovies = remember(movies, visibleCount) {
        movies.take(visibleCount.coerceIn(0, movies.size))
    }

    LaunchedEffect(sortState, imageMode) {
        val nextKey = "${sortState.option.name}:${sortState.direction.name}:${imageMode.name}"
        if (lastScrollResetKey != nextKey) {
            lastScrollResetKey = nextKey
            visibleCount = ALL_MOVIES_INITIAL_COUNT.coerceAtMost(movies.size)
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState, movies.size) {
        if (visibleCount == 0 && movies.isNotEmpty()) {
            visibleCount = ALL_MOVIES_INITIAL_COUNT.coerceAtMost(movies.size)
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { lastVisibleIndex ->
                if (
                    movies.size > visibleCount &&
                    lastVisibleIndex >= visibleCount - ALL_MOVIES_PREFETCH_THRESHOLD
                ) {
                    visibleCount = (visibleCount + ALL_MOVIES_PAGE_SIZE).coerceAtMost(movies.size)
                }
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(
            items = visibleMovies,
            key = { it.id },
            contentType = { if (imageMode == HomeImageMode.Thumb) "movie-thumb-card" else "movie-poster-card" }
        ) { movie ->
            MoviePosterCard(
                movie = movie,
                width = Dp.Unspecified,
                imageMode = imageMode,
                onClick = { onMovieClick(movie.id) },
                onPlay = { onPlay(movie) },
                onToggleFavorite = { onToggleFavorite(movie) },
                onToggleWatched = { onToggleWatched(movie) }
            )
        }
        if (visibleMovies.size < movies.size) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "已显示 ${visibleMovies.size} / ${movies.size}",
                    color = Color.White.copy(alpha = 0.42f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DomesticMoviesGrid(
    movies: List<DomesticMovieWithSources>,
    onPlay: (DomesticMovieWithSources, DomesticVideoSourceEntity) -> Unit
) {
    var sourcePickerMovie by remember { mutableStateOf<DomesticMovieWithSources?>(null) }
    if (movies.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无国产影片", color = Color.White.copy(alpha = 0.62f))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(
            items = movies,
            key = { it.movie.id },
            contentType = { "domestic-movie-row" }
        ) { movie ->
            DomesticMovieCard(
                item = movie,
                onClick = {
                    val sources = movie.sources
                    if (sources.size <= 1) {
                        sources.firstOrNull()?.let { onPlay(movie, it) }
                    } else {
                        sourcePickerMovie = movie
                    }
                }
            )
        }
    }

    sourcePickerMovie?.let { movie ->
        Dialog(onDismissRequest = { sourcePickerMovie = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF20242B))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "选择播放源",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = movie.movie.folderName,
                    color = Color.White.copy(alpha = 0.64f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                movie.sources.forEachIndexed { index, source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                sourcePickerMovie = null
                                onPlay(movie, source)
                            }
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MoviesAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "瑙嗛 ${index + 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = source.videoName,
                                color = Color.White.copy(alpha = 0.64f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DomesticMovieCard(
    item: DomesticMovieWithSources,
    onClick: () -> Unit
) {
    val movie = item.movie
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoviesPanel)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MoviesSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(movie.imageUrl)
                        .setHeader("User-Agent", USER_AGENT)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (movie.imageUrl.isNullOrBlank()) {
                    PosterPlaceholder(movie.folderName)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = movie.folderName,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (item.sources.size > 1) "${item.sources.size} 个视频源" else movie.videoName,
            color = Color.White.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LibrarySummaryGrid(
    title: String,
    summaries: List<MovieMetadataSummary>,
    emptyText: String,
    onClick: (MovieMetadataSummary) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "$title ${summaries.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
        if (summaries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(
                items = summaries,
                key = { it.value },
                contentType = { "metadata-summary-card" }
            ) { summary ->
                SummaryCard(summary = summary, onClick = { onClick(summary) })
            }
        }
    }
}

@Composable
private fun ActorSummaryGrid(
    summaries: List<MovieMetadataSummary>,
    refreshVersion: Int,
    onClick: (MovieMetadataSummary) -> Unit
) {
    val context = LocalContext.current
    val avatarStore = remember(context, refreshVersion) { ActorAvatarStore(context) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "演员 ${summaries.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        if (summaries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("还没有演员信息", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(
                items = summaries,
                key = { it.value },
                contentType = { "actor-summary-card" }
            ) { summary ->
                ActorSummaryCard(
                    summary = summary,
                    avatarUri = avatarStore.avatarUri(summary.value),
                    onClick = { onClick(summary) }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: MovieMetadataSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MoviesPanel)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = summary.value,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${summary.count} 部影片",
            color = Color.White.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ActorSummaryCard(summary: MovieMetadataSummary, avatarUri: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(9.dp))
                .background(MoviesSurface),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUri != null) {
                UriImage(uri = avatarUri, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxDecodeSize = 360)
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.82f), modifier = Modifier.size(38.dp))
            }
        }
        Text(
            text = summary.value,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${summary.count} 部",
            color = Color.White.copy(alpha = 0.50f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.48f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HorizontalMovieRail(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onToggleFavorite: (MovieEntity) -> Unit,
    onToggleWatched: (MovieEntity) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(
            items = movies,
            key = { it.id },
            contentType = { if (imageMode == HomeImageMode.Thumb) "rail-thumb-card" else "rail-poster-card" }
        ) { movie ->
            MoviePosterCard(
                movie = movie,
                width = if (imageMode == HomeImageMode.Thumb) 190.dp else 118.dp,
                imageMode = imageMode,
                onClick = { onMovieClick(movie.id) },
                onPlay = { onPlay(movie) },
                onToggleFavorite = { onToggleFavorite(movie) },
                onToggleWatched = { onToggleWatched(movie) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviePosterCard(
    movie: MovieEntity,
    width: Dp,
    imageMode: HomeImageMode = HomeImageMode.Poster,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit
) {
    var menuExpanded by remember(movie.id) { mutableStateOf(false) }
    val cardModifier = if (width == Dp.Unspecified) Modifier else Modifier.width(width)

    Column(modifier = cardModifier) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (imageMode == HomeImageMode.Thumb) 16f / 9f else 2f / 3f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true }
                    ),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MoviesSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MovieArtwork(
                        movie = movie,
                        preferThumb = imageMode == HomeImageMode.Thumb,
                        modifier = Modifier.fillMaxSize(),
                        maxDecodeSize = if (imageMode == HomeImageMode.Thumb) 960 else 720
                    )
                    if (movie.isFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(18.dp)
                        )
                    }
                }
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("\u64AD\u653E") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onPlay()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (movie.isFavorite) "\u53D6\u6D88\u6536\u85CF" else "\u52A0\u5165\u6536\u85CF") },
                    leadingIcon = {
                        Icon(
                            if (movie.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleFavorite()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (movie.isWatched) "\u6807\u8BB0\u672A\u89C2\u770B" else "\u6807\u8BB0\u5DF2\u89C2\u770B") },
                    leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToggleWatched()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\u67E5\u770B\u8BE6\u60C5") },
                    leadingIcon = { Icon(Icons.Rounded.MoreHoriz, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = movie.title,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        movie.year?.let {
            Text(
                text = it.toString(),
                color = Color.White.copy(alpha = 0.50f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF202A35), Color(0xFF10151B))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(2).uppercase(),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun EmptyLibraryState(
    isScanning: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(360.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MoviesPanel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "\u5F71\u7247\u5E93\u8FD8\u662F\u7A7A\u7684",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isScanning) {
                    "\u6B63\u5728\u626B\u63CF\u5A92\u4F53\u6587\u4EF6\u5939..."
                } else {
                    "\u9009\u62E9\u4E00\u4E2A\u5A92\u4F53\u5E93\u76EE\u5F55\u5F00\u59CB\u6574\u7406\u5F71\u7247"
                },
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodyMedium
            )
            AssistChip(
                onClick = { },
                label = { Text(if (isScanning) "\u626B\u63CF\u4E2D" else "\u8BF7\u5230\u8BBE\u7F6E\u4E2D\u9009\u62E9\u5F71\u7247\u5E93\u76EE\u5F55") }
            )
        }
    }
}

private fun scanMessage(scanState: ScanState): String = when (scanState) {
    ScanState.Idle -> "\u672C\u5730\u5F71\u7247\u3001\u6D77\u62A5\u548C NFO \u5143\u6570\u636E"
    ScanState.Scanning -> "\u6B63\u5728\u626B\u63CF\u6587\u4EF6\u5939..."
    is ScanState.Done -> "\u5DF2\u626B\u63CF ${scanState.count} \u90E8\u5F71\u7247"
    is ScanState.Error -> scanState.message
}

private enum class LibraryTab(val label: String) {
    All("全部"),
    Domestic("国产"),
    Collections("合集"),
    Actors("演员"),
    Tags("标签"),
    Genres("类型"),
    Years("年份"),
    Studios("工作室")
}

private fun List<String>.summaryValues(): List<MovieMetadataSummary> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, values) -> MovieMetadataSummary(values.first(), values.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase() })

private fun sortLabel(option: HomeSortOption): String = when (option) {
    HomeSortOption.ImdbRating -> "IMDb \u8BC4\u5206"
    HomeSortOption.Resolution -> "\u5206\u8FA8\u7387"
    HomeSortOption.DateAdded -> "\u52A0\u5165\u65E5\u671F"
    HomeSortOption.ReleaseDate -> "\u53D1\u884C\u65E5\u671F"
    HomeSortOption.MediaContainer -> "\u5A92\u4F53\u5BB9\u5668"
    HomeSortOption.ParentalRating -> "\u5BB6\u957F\u8BC4\u5206"
    HomeSortOption.Director -> "\u5BFC\u6F14"
    HomeSortOption.FrameRate -> "\u5E27\u7387"
    HomeSortOption.Title -> "\u6807\u9898"
    HomeSortOption.Year -> "\u5E74\u4EFD"
    HomeSortOption.CriticRating -> "\u5F71\u8BC4\u4EBA\u8BC4\u5206"
    HomeSortOption.PlayDate -> "\u64AD\u653E\u65E5\u671F"
    HomeSortOption.PlayDuration -> "\u64AD\u653E\u65F6\u957F"
    HomeSortOption.PlayCount -> "\u64AD\u653E\u6B21\u6570"
    HomeSortOption.FileName -> "\u6587\u4EF6\u540D"
    HomeSortOption.FileSize -> "\u6587\u4EF6\u5C3A\u5BF8"
    HomeSortOption.Bitrate -> "\u6BD4\u7279\u7387"
    HomeSortOption.VideoCodec -> "\u89C6\u9891\u7F16\u89E3\u7801\u5668"
    HomeSortOption.Random -> "\u968F\u673A"
}
