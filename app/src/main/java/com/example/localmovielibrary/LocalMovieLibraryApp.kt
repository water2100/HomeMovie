package com.example.localmovielibrary

import android.app.Application
import com.example.localmovielibrary.data.AppContainer

class LocalMovieLibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
