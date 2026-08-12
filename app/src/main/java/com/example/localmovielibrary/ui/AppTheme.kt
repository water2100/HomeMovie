package com.example.localmovielibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF36C5F0),
    background = Color(0xFF070A0E),
    surface = Color(0xFF111720),
    onBackground = Color(0xFFF4F7FA),
    onSurface = Color(0xFFF4F7FA)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006D8F),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF172027),
    onSurface = Color(0xFF172027)
)

@Composable
fun HomeMovieTheme(useLightTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (useLightTheme) LightColors else DarkColors, content = content)
}
