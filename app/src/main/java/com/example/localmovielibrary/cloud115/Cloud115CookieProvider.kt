package com.example.localmovielibrary.cloud115

import android.content.Context

class Cloud115CookieProvider(private val context: Context) {
    fun loadCookies(): String? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIES, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (stored != null) return stored

        return runCatching {
            context.assets.open(ASSET_COOKIE_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val PREFS_NAME = "cloud115"
        const val KEY_COOKIES = "cookies"
        const val ASSET_COOKIE_FILE = "115-cookies.txt"
    }
}
