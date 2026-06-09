package com.example.localmovielibrary.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.example.localmovielibrary.data.local.MovieEntity

@Composable
fun MovieArtwork(
    movie: MovieEntity,
    preferThumb: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDecodeSize: Int = if (preferThumb) 960 else 720
) {
    val imageUri = movie.selectArtworkUri(preferThumb)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        UriImage(
            uri = imageUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            maxDecodeSize = maxDecodeSize,
            cacheKey = movie.artworkCacheRevision()
        )
        if (imageUri == null) {
            MovieArtworkPlaceholder(title = movie.title)
        }
    }
}

fun MovieEntity.selectArtworkUri(preferThumb: Boolean): String? =
    if (preferThumb) {
        thumbUri ?: fanartUri ?: posterUri
    } else {
        posterUri ?: thumbUri ?: fanartUri
    }

fun MovieEntity.artworkCacheRevision(): String =
    listOf(
        id.toString(),
        maxOf(updatedAt, scannedAtMillis).toString(),
        posterUri.orEmpty(),
        fanartUri.orEmpty(),
        thumbUri.orEmpty()
    ).joinToString("|")

@Composable
fun MovieArtworkPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF202A35), Color(0xFF10151B)))),
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
