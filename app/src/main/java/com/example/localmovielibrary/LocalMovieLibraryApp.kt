package com.example.localmovielibrary

import android.app.Application
import android.database.CursorWindow
import android.util.Log
import com.example.localmovielibrary.data.AppContainer

class LocalMovieLibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, BUILD_MARKER)
        configureCursorWindow()
        container = AppContainer(this)
    }

    private fun configureCursorWindow() {
        runCatching {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val currentSize = field.getInt(null)
            if (currentSize < CURSOR_WINDOW_SIZE_BYTES) {
                field.setInt(null, CURSOR_WINDOW_SIZE_BYTES)
            }
        }
    }

    private companion object {
        private const val TAG = "LocalMovieLibraryApp"
        private const val BUILD_MARKER = "BUILD_MARKER=2026-06-12-room-paged-dao-player-subtitles"
        private const val CURSOR_WINDOW_SIZE_BYTES = 64 * 1024 * 1024
    }
}
