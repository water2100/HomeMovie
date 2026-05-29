package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115CookieProvider
import com.example.localmovielibrary.scraper.ScrapeSource

class AppSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

    fun getCookies(): String = prefs.getString(Cloud115CookieProvider.KEY_COOKIES, null).orEmpty()

    fun saveCookies(value: String) {
        prefs.edit().putString(Cloud115CookieProvider.KEY_COOKIES, value.trim()).apply()
    }

    fun getMissavCookies(): String = prefs.getString(KEY_MISSAV_COOKIES, null).orEmpty()

    fun saveMissavCookies(value: String) {
        prefs.edit().putString(KEY_MISSAV_COOKIES, value.trim()).commit()
    }

    fun getStrmTreeUri(): String? = prefs.getString(KEY_STRM_TREE_URI, null)

    fun getStrmTreeDisplayName(): String = getTreeDisplayName(getStrmTreeUri()) ?: "尚未选择目录"

    fun saveStrmTreeUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val uriString = uri.toString()
        prefs.edit()
            .putString(KEY_STRM_TREE_URI, uriString)
            .putStringSet(KEY_STRM_TREE_URI_HISTORY, getKnownStrmTreeUris() + uriString)
            .apply()
    }

    fun getLibraryRootUri(): String? = prefs.getString(KEY_LIBRARY_ROOT_URI, null)

    fun getLibraryRootDisplayName(): String = getTreeDisplayName(getLibraryRootUri()) ?: "尚未选择目录"

    fun saveLibraryRootUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val uriString = uri.toString()
        prefs.edit()
            .putString(KEY_LIBRARY_ROOT_URI, uriString)
            .putStringSet(KEY_LIBRARY_ROOT_URI_HISTORY, getKnownLibraryRootUris() + uriString)
            .apply()
    }

    fun getKnownStrmTreeUris(): Set<String> =
        (prefs.getStringSet(KEY_STRM_TREE_URI_HISTORY, emptySet()) ?: emptySet()) +
            listOfNotNull(getStrmTreeUri())

    fun getKnownLibraryRootUris(): Set<String> =
        (prefs.getStringSet(KEY_LIBRARY_ROOT_URI_HISTORY, emptySet()) ?: emptySet()) +
            listOfNotNull(getLibraryRootUri())

    fun getStrmBaseUrl(): String {
        val stored = prefs.getString(KEY_STRM_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        return when (stored) {
            null, OLD_DEFAULT_STRM_BASE_URL -> DEFAULT_STRM_BASE_URL
            else -> stored
        }
    }

    fun saveStrmBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/').ifBlank { DEFAULT_STRM_BASE_URL }
        prefs.edit().putString(KEY_STRM_BASE_URL, normalized).apply()
    }

    fun getDefaultScrapeSource(): ScrapeSource {
        val stored = prefs.getString(KEY_DEFAULT_SCRAPE_SOURCE, null)
        return ScrapeSource.entries.firstOrNull { it.name == stored } ?: ScrapeSource.Official
    }

    fun saveDefaultScrapeSource(source: ScrapeSource) {
        prefs.edit().putString(KEY_DEFAULT_SCRAPE_SOURCE, source.name).apply()
    }

    fun getImageDownloadRetryCount(): Int {
        return prefs.getInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
            .coerceIn(1, 10)
    }

    fun saveImageDownloadRetryCount(count: Int) {
        prefs.edit().putInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, count.coerceIn(1, 10)).apply()
    }

    fun getHomeSortOptionName(): String? = prefs.getString(KEY_HOME_SORT_OPTION, null)

    fun saveHomeSortOptionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_OPTION, value).apply()
    }

    fun getHomeSortDirectionName(): String? = prefs.getString(KEY_HOME_SORT_DIRECTION, null)

    fun saveHomeSortDirectionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_DIRECTION, value).apply()
    }

    fun getHomeImageModeName(): String? = prefs.getString(KEY_HOME_IMAGE_MODE, null)

    fun saveHomeImageModeName(value: String) {
        prefs.edit().putString(KEY_HOME_IMAGE_MODE, value).apply()
    }

    fun getBaiduTranslateAppId(): String =
        prefs.getString(KEY_BAIDU_TRANSLATE_APP_ID, null)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    fun saveBaiduTranslateAppId(value: String) {
        prefs.edit().putString(KEY_BAIDU_TRANSLATE_APP_ID, value.trim()).apply()
    }

    fun getBaiduTranslateSecretKey(): String =
        prefs.getString(KEY_BAIDU_TRANSLATE_SECRET_KEY, null)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    fun saveBaiduTranslateSecretKey(value: String) {
        prefs.edit().putString(KEY_BAIDU_TRANSLATE_SECRET_KEY, value.trim()).apply()
    }

    fun isDetailThumbBackgroundEnabled(): Boolean =
        prefs.getBoolean(KEY_DETAIL_THUMB_BACKGROUND_ENABLED, false)

    fun saveDetailThumbBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DETAIL_THUMB_BACKGROUND_ENABLED, enabled).apply()
    }

    fun getDetailThumbBackgroundAlphaPercent(): Int =
        prefs.getInt(KEY_DETAIL_THUMB_BACKGROUND_ALPHA, DEFAULT_DETAIL_THUMB_BACKGROUND_ALPHA)
            .coerceIn(0, 100)

    fun saveDetailThumbBackgroundAlphaPercent(percent: Int) {
        prefs.edit().putInt(KEY_DETAIL_THUMB_BACKGROUND_ALPHA, percent.coerceIn(0, 100)).apply()
    }

    private fun getTreeDisplayName(uriString: String?): String? {
        val uri = uriString?.let { Uri.parse(it) } ?: return null
        DocumentFile.fromTreeUri(appContext, uri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val OLD_DEFAULT_STRM_BASE_URL = "http://118.145.114.4:5000"
        const val DEFAULT_STRM_BASE_URL = "http://127.0.0.1"
        const val KEY_STRM_TREE_URI = "strm_tree_uri"
        const val KEY_STRM_TREE_URI_HISTORY = "strm_tree_uri_history"
        const val KEY_STRM_BASE_URL = "strm_base_url"
        const val KEY_LIBRARY_ROOT_URI = "library_root_uri"
        const val KEY_LIBRARY_ROOT_URI_HISTORY = "library_root_uri_history"
        const val KEY_MISSAV_COOKIES = "missav_cookies"
        const val KEY_DEFAULT_SCRAPE_SOURCE = "default_scrape_source"
        const val KEY_IMAGE_DOWNLOAD_RETRY_COUNT = "image_download_retry_count"
        const val KEY_HOME_SORT_OPTION = "home_sort_option"
        const val KEY_HOME_SORT_DIRECTION = "home_sort_direction"
        const val KEY_HOME_IMAGE_MODE = "home_image_mode"
        const val KEY_BAIDU_TRANSLATE_APP_ID = "baidu_translate_app_id"
        const val KEY_BAIDU_TRANSLATE_SECRET_KEY = "baidu_translate_secret_key"
        const val KEY_DETAIL_THUMB_BACKGROUND_ENABLED = "detail_thumb_background_enabled"
        const val KEY_DETAIL_THUMB_BACKGROUND_ALPHA = "detail_thumb_background_alpha"
        const val DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT = 5
        const val DEFAULT_DETAIL_THUMB_BACKGROUND_ALPHA = 32
        const val DEFAULT_BAIDU_TRANSLATE_APP_ID = ""
        const val DEFAULT_BAIDU_TRANSLATE_SECRET_KEY = ""
    }
}
