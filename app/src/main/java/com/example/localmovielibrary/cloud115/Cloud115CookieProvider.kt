package com.example.localmovielibrary.cloud115

import android.content.Context
import java.io.File

class Cloud115CookieProvider(private val context: Context) {
    fun loadCookies(): String? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIES, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (stored != null) return stored

        findSavedCookieFile()?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        findAssetCookieFile()?.let { assetName ->
            return runCatching {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }

        return null
    }

    private fun findSavedCookieFile(): File? =
        File(context.filesDir, COOKIE_DIR)
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith(COOKIE_FILE_PREFIX) &&
                    file.name.endsWith(COOKIE_FILE_SUFFIX)
            }
            ?.sortedBy { it.name.lowercase() }
            ?.firstOrNull()

    private fun findAssetCookieFile(): String? {
        val files = context.assets.list("").orEmpty()
        return files
            .filter { it.startsWith(COOKIE_FILE_PREFIX) && it.endsWith(COOKIE_FILE_SUFFIX) }
            .sorted()
            .firstOrNull()
            ?: files.firstOrNull { it == LEGACY_ASSET_COOKIE_FILE }
    }

    companion object {
        const val PREFS_NAME = "cloud115"
        const val KEY_COOKIES = "cookies"
        const val COOKIE_DIR = "115cookies"
        const val COOKIE_FILE_PREFIX = "115cookie_"
        const val COOKIE_FILE_SUFFIX = ".txt"
        const val LEGACY_ASSET_COOKIE_FILE = "115-cookies.txt"
    }
}
