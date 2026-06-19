package com.example.localmovielibrary.ui.search

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.ui.home.HomeImageMode
import com.example.localmovielibrary.ui.shared.MovieArtwork
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

private val SearchBackground = Color(0xFF070A0E)
private val SearchPanel = Color.White.copy(alpha = 0.075f)
private const val SEARCH_INITIAL_COUNT = 90
private const val SEARCH_PAGE_SIZE = 60
private const val SEARCH_PREFETCH_THRESHOLD = 18

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onMovieClick: (Long) -> Unit,
    imageMode: HomeImageMode = HomeImageMode.Poster
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SearchBackground)
    ) {
        SearchHeader(
            query = uiState.query,
            selectedScope = uiState.scope,
            onQueryChange = viewModel::updateQuery,
            onClear = viewModel::clearQuery,
            onScopeSelected = viewModel::setScope
        )

        when {
            !uiState.hasQuery -> SearchEmptyPrompt(uiState.minSearchLength)
            uiState.results.isEmpty() -> SearchNoResults()
            else -> SearchResultsGrid(
                movies = uiState.results,
                imageMode = imageMode,
                resetKey = "${uiState.scope.name}:${uiState.query}",
                onMovieClick = onMovieClick
            )
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    selectedScope: SearchScope,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onScopeSelected: (SearchScope) -> Unit
) {
    var queryFieldValue by remember { mutableStateOf(TextFieldValue(query, selection = TextRange(query.length))) }

    LaunchedEffect(query) {
        if (query != queryFieldValue.text) {
            queryFieldValue = TextFieldValue(query, selection = TextRange(query.length))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), SearchBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u641C\u7D22",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = queryFieldValue,
            onValueChange = { value ->
                queryFieldValue = value
                onQueryChange(value.text)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                    }
                }
            },
            placeholder = { Text("\u641C\u7D22\u6807\u9898\u3001\u6F14\u5458\u3001\u756A\u53F7\u3001\u7C7B\u578B\u3001\u6807\u7B7E\u6216\u5E74\u4EFD") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.42f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                focusedLeadingIconColor = Color.White.copy(alpha = 0.82f),
                unfocusedLeadingIconColor = Color.White.copy(alpha = 0.62f),
                focusedTrailingIconColor = Color.White.copy(alpha = 0.82f),
                unfocusedTrailingIconColor = Color.White.copy(alpha = 0.62f),
                focusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.45f)
            )
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                count = SearchScope.entries.size,
                key = { SearchScope.entries[it].name },
                contentType = { "search-scope-chip" }
            ) { index ->
                val scope = SearchScope.entries[index]
                FilterChip(
                    selected = selectedScope == scope,
                    onClick = { onScopeSelected(scope) },
                    label = { Text(searchScopeLabel(scope)) }
                )
            }
        }
    }
}

@Composable
private fun SearchResultsGrid(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    resetKey: String,
    onMovieClick: (Long) -> Unit
) {
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var visibleCount by rememberSaveable { mutableStateOf(SEARCH_INITIAL_COUNT) }
    val visibleMovies = remember(movies, visibleCount) {
        movies.take(visibleCount.coerceIn(0, movies.size))
    }

    LaunchedEffect(resetKey) {
        visibleCount = SEARCH_INITIAL_COUNT.coerceAtMost(movies.size)
        gridState.scrollToItem(0)
    }

    LaunchedEffect(movies.size) {
        if (visibleCount == 0 && movies.isNotEmpty()) {
            visibleCount = SEARCH_INITIAL_COUNT.coerceAtMost(movies.size)
        } else if (visibleCount > movies.size) {
            visibleCount = movies.size
        }
    }

    LaunchedEffect(gridState, movies.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { lastVisibleIndex ->
                if (
                    movies.size > visibleCount &&
                    lastVisibleIndex >= visibleCount - SEARCH_PREFETCH_THRESHOLD
                ) {
                    visibleCount = (visibleCount + SEARCH_PAGE_SIZE).coerceAtMost(movies.size)
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "\u627E\u5230 ${movies.size} \u90E8\u5F71\u7247",
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = if (imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            items(
                items = visibleMovies,
                key = { it.id },
                contentType = { if (imageMode == HomeImageMode.Thumb) "search-thumb-card" else "search-poster-card" }
            ) { movie ->
                SearchMoviePosterCard(movie = movie, imageMode = imageMode, onClick = { onMovieClick(movie.id) })
            }
        }
    }
}

@Composable
private fun SearchMoviePosterCard(movie: MovieEntity, imageMode: HomeImageMode, onClick: () -> Unit) {
    Column {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (imageMode == HomeImageMode.Thumb) 16f / 9f else 2f / 3f),
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF111720)),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
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
private fun SearchEmptyPrompt(minSearchLength: Int) {
    Box(modifier = Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "\u8F93\u5165\u81F3\u5C11 $minSearchLength \u4E2A\u5B57\u7B26\uFF0C\u641C\u7D22\u672C\u5730\u5F71\u7247\u5E93",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun SearchNoResults() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(SearchPanel),
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

private fun searchScopeLabel(scope: SearchScope): String = when (scope) {
    SearchScope.All -> "\u5168\u90E8"
    SearchScope.Title -> "\u6807\u9898"
    SearchScope.Number -> "\u756A\u53F7"
    SearchScope.Actor -> "\u6F14\u5458"
    SearchScope.Tag -> "\u6807\u7B7E"
    SearchScope.Genre -> "\u7C7B\u578B"
}
