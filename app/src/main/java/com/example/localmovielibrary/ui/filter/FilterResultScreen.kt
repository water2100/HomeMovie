package com.example.localmovielibrary.ui.filter

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.ui.home.HomeImageMode
import com.example.localmovielibrary.ui.shared.MovieArtwork
import kotlinx.coroutines.flow.collectLatest

private val ResultBackground = Color(0xFF070A0E)
private val ResultPanel = Color.White.copy(alpha = 0.075f)
private const val FILTER_INITIAL_COUNT = 90
private const val FILTER_PAGE_SIZE = 60
private const val FILTER_PREFETCH_THRESHOLD = 18

@Composable
fun FilterResultScreen(
    viewModel: FilterResultViewModel,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    imageMode: HomeImageMode = HomeImageMode.Poster
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var visibleCount by rememberSaveable { mutableStateOf(FILTER_INITIAL_COUNT) }
    val visibleMovies = remember(uiState.movies, visibleCount) {
        uiState.movies.take(visibleCount.coerceIn(0, uiState.movies.size))
    }

    LaunchedEffect(uiState.filterType, uiState.filterValue) {
        visibleCount = FILTER_INITIAL_COUNT.coerceAtMost(uiState.movies.size)
        gridState.scrollToItem(0)
    }

    LaunchedEffect(uiState.movies.size) {
        if (visibleCount == 0 && uiState.movies.isNotEmpty()) {
            visibleCount = FILTER_INITIAL_COUNT.coerceAtMost(uiState.movies.size)
        } else if (visibleCount > uiState.movies.size) {
            visibleCount = uiState.movies.size
        }
    }

    LaunchedEffect(gridState, uiState.movies.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { lastVisibleIndex ->
                if (
                    uiState.movies.size > visibleCount &&
                    lastVisibleIndex >= visibleCount - FILTER_PREFETCH_THRESHOLD
                ) {
                    visibleCount = (visibleCount + FILTER_PAGE_SIZE).coerceAtMost(uiState.movies.size)
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ResultBackground)
    ) {
        FilterResultTopBar(
            title = uiState.title,
            count = uiState.movies.size,
            onBack = onBack
        )
        if (uiState.movies.isEmpty()) {
            FilterEmptyState()
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                items(
                    items = visibleMovies,
                    key = { it.id },
                    contentType = { if (imageMode == HomeImageMode.Thumb) "filter-thumb-card" else "filter-poster-card" }
                ) { movie ->
                    ResultPosterCard(movie = movie, imageMode = imageMode, onClick = { onMovieClick(movie.id) })
                }
            }
        }
    }
}

@Composable
private fun FilterResultTopBar(
    title: String,
    count: Int,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), ResultBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 10.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
            onClick = onBack
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "\u7B5B\u9009\u7ED3\u679C" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$count \u90E8\u5F71\u7247",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ResultPosterCard(movie: MovieEntity, imageMode: HomeImageMode, onClick: () -> Unit) {
    Column {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (imageMode == HomeImageMode.Thumb) 16f / 9f else 2f / 3f),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111720)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            MovieArtwork(
                movie = movie,
                preferThumb = imageMode == HomeImageMode.Thumb,
                modifier = Modifier.fillMaxSize(),
                maxDecodeSize = if (imageMode == HomeImageMode.Thumb) 960 else 720
            )
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
private fun FilterEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ResultPanel),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\u6CA1\u6709\u627E\u5230\u76F8\u5173\u5F71\u7247",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
