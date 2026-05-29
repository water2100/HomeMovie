package com.example.localmovielibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.localmovielibrary.ui.LocalMovieLibraryAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as LocalMovieLibraryApp).container
        setContent {
            AppTheme {
                LocalMovieLibraryAppRoot(appContainer = appContainer)
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF7ED8C3),
        secondary = Color(0xFFE7C267),
        tertiary = Color(0xFF8FB8FF),
        background = Color(0xFF0E1110),
        surface = Color(0xFF161B19)
    )
    val lightColors = lightColorScheme(
        primary = Color(0xFF23685B),
        secondary = Color(0xFF7B5A00),
        tertiary = Color(0xFF315F9F)
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
        content = content
    )
}
