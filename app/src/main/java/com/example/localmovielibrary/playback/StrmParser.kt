package com.example.localmovielibrary.playback

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StrmParser(private val contentResolver: ContentResolver) {
    suspend fun readEntryUrl(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val line = contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                lines.map { it.trim() }.firstOrNull { it.isNotBlank() }
            }
            line?.takeIf { it.isNotBlank() } ?: error("STRM file is empty")
        }
    }
}
