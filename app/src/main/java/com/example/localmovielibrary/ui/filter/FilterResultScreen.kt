package com.example.localmovielibrary.ui.filter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.ui.home.HomeImageMode
import com.example.localmovielibrary.ui.shared.UriImage

private val ResultBackground = Color(0xFF070A0E)
private val ResultPanel = Color.White.copy(alpha = 0.075f)

@Composable
fun FilterResultScreen(
    viewModel: FilterResultViewModel,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    imageMode: HomeImageMode = HomeImageMode.Poster
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                columns = GridCells.Adaptive(minSize = if (imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                items(uiState.movies, key = { it.id }) { movie ->
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
            .padding(start = 10.dp, end = 18.dp, top = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
            onClick = onBack
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "\u7B5B\u9009\u7ED3\u679C" },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UriImage(
                    uri = if (imageMode == HomeImageMode.Thumb) {
                        movie.thumbUri ?: movie.fanartUri ?: movie.posterUri
                    } else {
                        movie.posterUri ?: movie.thumbUri ?: movie.fanartUri
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    maxDecodeSize = 520
                )
                if (movie.posterUri == null && movie.thumbUri == null && movie.fanartUri == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xFF202A35), Color(0xFF10151B)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = movie.title.take(2).uppercase(),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
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
