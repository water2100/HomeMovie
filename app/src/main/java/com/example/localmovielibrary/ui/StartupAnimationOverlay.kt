package com.example.localmovielibrary.ui

import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

@Composable
internal fun StartupAnimationOverlay(imageUri: String, onFinished: () -> Unit) {
    var shouldFadeOut by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (shouldFadeOut) 0f else 1f,
        animationSpec = tween(320),
        label = "startupFade"
    )
    val transition = rememberInfiniteTransition(label = "startupImage")
    val imageScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1_700), RepeatMode.Reverse),
        label = "startupImageScale"
    )

    LaunchedEffect(Unit) {
        delay(1_250)
        shouldFadeOut = true
        delay(320)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Brush.verticalGradient(listOf(Color(0xFF08121C), Color(0xFF020406))))
    ) {
        if (imageUri.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(imageUri)).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(imageScale).alpha(0.68f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("HomeMovie", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("正在进入影片库", color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(top = 8.dp))
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 28.dp).size(30.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 42.dp)
                .width(72.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.75f))
        )
    }
}
