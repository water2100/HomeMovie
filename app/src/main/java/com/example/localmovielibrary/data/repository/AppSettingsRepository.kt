package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115CookieProvider
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.util.NumberRecognitionRules
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.net.Proxy

class AppSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        NumberRecognitionRules.updateIgnoredSuffixes(getCachedNumberRecognitionIgnoredSuffixes())
        NumberRecognitionRules.updatePartMarkers(getCachedNumberRecognitionPartMarkers())
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
    }

    fun getCookies(): String = prefs.getString(Cloud115CookieProvider.KEY_COOKIES, null).orEmpty()

    fun saveCookies(value: String) {
        prefs.edit().putString(Cloud115CookieProvider.KEY_COOKIES, value.trim()).apply()
    }

    fun getCloud115LoginApp(): String =
        prefs.getString(KEY_CLOUD115_LOGIN_APP, null)
            ?.takeIf { app -> Cloud115LoginApps.all.any { it.app == app } }
            ?: Cloud115LoginApps.default.app

    fun saveCloud115LoginApp(value: String) {
        val normalized = Cloud115LoginApps.find(value).app
        prefs.edit().putString(KEY_CLOUD115_LOGIN_APP, normalized).apply()
    }

    fun getAvsubtitlesCookies(): String = prefs.getString(KEY_AVSUBTITLES_COOKIES, null).orEmpty()

    fun saveAvsubtitlesCookies(value: String) {
        prefs.edit().putString(KEY_AVSUBTITLES_COOKIES, value.trim()).commit()
    }

    fun getSubtitleSearchProvider(): SubtitleSearchProvider =
        SubtitleSearchProvider.fromId(prefs.getString(KEY_SUBTITLE_SEARCH_PROVIDER, null))

    fun saveSubtitleSearchProvider(provider: SubtitleSearchProvider) {
        prefs.edit().putString(KEY_SUBTITLE_SEARCH_PROVIDER, provider.id).apply()
    }

    fun getUpdateManifestUrl(): String =
        prefs.getString(KEY_UPDATE_MANIFEST_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_MANIFEST_URL

    fun saveUpdateManifestUrl(value: String) {
        prefs.edit()
            .putString(KEY_UPDATE_MANIFEST_URL, value.trim().ifBlank { DEFAULT_UPDATE_MANIFEST_URL })
            .apply()
    }

    fun getUpdateProxyBaseUrl(): String {
        val stored = prefs.getString(KEY_UPDATE_PROXY_BASE_URL, null)
        return if (stored == null) DEFAULT_UPDATE_PROXY_BASE_URL else normalizeUpdateProxyBaseUrl(stored)
    }

    fun saveUpdateProxyBaseUrl(value: String) {
        prefs.edit().putString(KEY_UPDATE_PROXY_BASE_URL, normalizeUpdateProxyBaseUrl(value)).apply()
    }

    fun isUpdateProxyEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_PROXY_ENABLED, true)

    fun saveUpdateProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_PROXY_ENABLED, enabled).apply()
    }

    fun isUpdateAutoCheckOnStartupEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED, true)

    fun saveUpdateAutoCheckOnStartupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED, enabled).apply()
    }

    fun isUpdateAutoDeleteInstalledApkEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED, true)

    fun saveUpdateAutoDeleteInstalledApkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED, enabled).apply()
    }

    fun isStartupAnimationEnabled(): Boolean =
        prefs.getBoolean(KEY_STARTUP_ANIMATION_ENABLED, false)

    fun saveStartupAnimationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STARTUP_ANIMATION_ENABLED, enabled).apply()
    }

    fun getStartupAnimationImageUri(): String =
        prefs.getString(KEY_STARTUP_ANIMATION_IMAGE_URI, null).orEmpty()

    fun saveStartupAnimationImageUri(uri: String) {
        prefs.edit().putString(KEY_STARTUP_ANIMATION_IMAGE_URI, uri).apply()
    }

    fun clearStartupAnimationImageUri() {
        prefs.edit().remove(KEY_STARTUP_ANIMATION_IMAGE_URI).apply()
    }

    fun getPendingUpdateInstallVersionCode(): Int =
        prefs.getInt(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE, 0)

    fun getPendingUpdateInstallApkPath(): String =
        prefs.getString(KEY_PENDING_UPDATE_INSTALL_APK_PATH, null).orEmpty()

    fun savePendingUpdateInstallApk(versionCode: Int, apkPath: String) {
        prefs.edit()
            .putInt(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE, versionCode.coerceAtLeast(0))
            .putString(KEY_PENDING_UPDATE_INSTALL_APK_PATH, apkPath)
            .apply()
    }

    fun clearPendingUpdateInstallApk() {
        prefs.edit()
            .remove(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE)
            .remove(KEY_PENDING_UPDATE_INSTALL_APK_PATH)
            .apply()
    }

    fun getStrmTreeUri(): String? = getLibraryRootUri()

    fun getStrmTreeDisplayName(): String = getLibraryRootDisplayName()

    fun saveStrmTreeUri(uri: Uri) {
        saveLibraryRootUri(uri)
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
            .putString(KEY_STRM_TREE_URI, uriString)
            .putStringSet(KEY_LIBRARY_ROOT_URI_HISTORY, getKnownLibraryRootUris() + uriString)
            .putStringSet(KEY_STRM_TREE_URI_HISTORY, getKnownStrmTreeUris() + uriString)
            .apply()
        syncNoMediaForLibraryRoot(uriString)
    }

    fun getKnownStrmTreeUris(): Set<String> = getKnownLibraryRootUris()

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
        return ScrapeSource.entries.firstOrNull { it.name == stored && it == ScrapeSource.Priority } ?: ScrapeSource.Priority
    }

    fun saveDefaultScrapeSource(source: ScrapeSource) {
        prefs.edit().putString(KEY_DEFAULT_SCRAPE_SOURCE, ScrapeSource.Priority.name).apply()
    }

    fun getPriorityScrapeSources(): List<ScrapeSource> {
        val stored = prefs.getString(KEY_PRIORITY_SCRAPE_SOURCES, null)
            ?.split(",")
            ?.mapNotNull { name ->
                ScrapeSource.fromStoredName(name)
            }
            ?.filter { it in PRIORITY_SCRAPE_SOURCE_OPTIONS }
            ?.distinct()
            .orEmpty()
        return when {
            stored.isEmpty() -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            stored == OLD_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            stored == OLD_DMM_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            else -> stored
        }
    }

    fun savePriorityScrapeSources(sources: List<ScrapeSource>) {
        val normalized = sources
            .filter { it in PRIORITY_SCRAPE_SOURCE_OPTIONS }
            .distinct()
            .ifEmpty { DEFAULT_PRIORITY_SCRAPE_SOURCES }
        prefs.edit()
            .putString(KEY_PRIORITY_SCRAPE_SOURCES, normalized.joinToString(",") { it.name })
            .apply()
    }

    fun getImageDownloadRetryCount(): Int {
        return prefs.getInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
            .coerceIn(1, 10)
    }

    fun saveImageDownloadRetryCount(count: Int) {
        prefs.edit().putInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, count.coerceIn(1, 10)).apply()
    }

    fun getScrapeConcurrencyLimit(): Int {
        return prefs.getInt(KEY_SCRAPE_CONCURRENCY_LIMIT, DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
            .coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)
    }

    fun saveScrapeConcurrencyLimit(count: Int) {
        prefs.edit().putInt(KEY_SCRAPE_CONCURRENCY_LIMIT, count.coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)).apply()
    }

    fun getScrapeProxyAddress(): String = prefs.getString(KEY_SCRAPE_PROXY_ADDRESS, null).orEmpty().trim()

    fun saveScrapeProxyAddress(value: String) {
        prefs.edit().putString(KEY_SCRAPE_PROXY_ADDRESS, value.trim()).apply()
    }

    fun isScrapeProxyEnabled(): Boolean = prefs.getBoolean(KEY_SCRAPE_PROXY_ENABLED, false)

    fun saveScrapeProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCRAPE_PROXY_ENABLED, enabled).apply()
    }

    /** Returns null for an empty or malformed address, which lets requests connect directly. */
    fun getScrapeProxy(): Proxy? {
        if (!isScrapeProxyEnabled()) return null
        val raw = getScrapeProxyAddress()
        if (raw.isBlank()) return null
        val uri = runCatching {
            java.net.URI(if ("://" in raw) raw else "http://$raw")
        }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val type = if (uri.scheme.equals("socks5", ignoreCase = true) || uri.scheme.equals("socks", ignoreCase = true)) {
            Proxy.Type.SOCKS
        } else {
            Proxy.Type.HTTP
        }
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }

    fun getDmm2SkippedNumberPrefixes(): Set<String> =
        prefs.getStringSet(KEY_DMM2_SKIPPED_NUMBER_PREFIXES, DEFAULT_DMM2_SKIPPED_NUMBER_PREFIXES)
            .orEmpty()
            .mapNotNull { it.normalizedNumberPrefixOrNull() }
            .toSet()

    fun saveDmm2SkippedNumberPrefixes(prefixes: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_DMM2_SKIPPED_NUMBER_PREFIXES, prefixes.mapNotNull { it.normalizedNumberPrefixOrNull() }.toSet())
            .apply()
    }

    fun addDmm2SkippedNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedNumberPrefixOrNull() ?: return
        saveDmm2SkippedNumberPrefixes(getDmm2SkippedNumberPrefixes() + normalized)
    }

    fun removeDmm2SkippedNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedNumberPrefixOrNull() ?: return
        saveDmm2SkippedNumberPrefixes(getDmm2SkippedNumberPrefixes() - normalized)
    }

    fun getRemoteScrapeConfigUrl(): String =
        prefs.getString(KEY_REMOTE_SCRAPE_CONFIG_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REMOTE_SCRAPE_CONFIG_URL

    fun saveRemoteScrapeConfigUrl(value: String) {
        prefs.edit()
            .putString(KEY_REMOTE_SCRAPE_CONFIG_URL, value.trim().ifBlank { DEFAULT_REMOTE_SCRAPE_CONFIG_URL })
            .apply()
    }

    fun getCachedMgstageNumberPrefixes(): Set<String> =
        (DEFAULT_MGSTAGE_NUMBER_PREFIXES + prefs.getStringSet(KEY_CACHED_MGSTAGE_NUMBER_PREFIXES, emptySet()).orEmpty())
            .orEmpty()
            .mapNotNull { it.normalizedNumberPrefixOrNull() }
            .filterNot { it == "CUTE" || it.firstOrNull()?.isDigit() == true }
            .toSet()
            .plus(getCachedMgstageSearchPrefixAliases().keys)

    fun saveCachedMgstageNumberPrefixes(prefixes: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_CACHED_MGSTAGE_NUMBER_PREFIXES, prefixes.mapNotNull { it.normalizedNumberPrefixOrNull() }.toSet())
            .apply()
    }

    fun getCachedMgstageSearchPrefixAliases(): Map<String, String> =
        DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES + (prefs.getString(KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES, null)
            ?.let { parseMgstageSearchPrefixAliases(it) }
            .orEmpty())

    fun saveCachedMgstageSearchPrefixAliases(aliases: Map<String, String>) {
        val normalized = normalizeMgstageSearchPrefixAliases(aliases)
        prefs.edit()
            .putString(
                KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES,
                normalized.toJsonObjectString()
            )
            .apply()
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
    }

    fun getCachedNumberRecognitionIgnoredSuffixes(): Set<String> =
        prefs.getStringSet(KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES, DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES)
            .orEmpty()
            .let(NumberRecognitionRules::normalizeIgnoredSuffixes)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES }

    fun saveCachedNumberRecognitionIgnoredSuffixes(suffixes: Set<String>) {
        val normalized = NumberRecognitionRules.normalizeIgnoredSuffixes(suffixes)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES }
        prefs.edit()
            .putStringSet(KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES, normalized)
            .apply()
        NumberRecognitionRules.updateIgnoredSuffixes(normalized)
    }

    fun getCachedNumberRecognitionPartMarkers(): Set<String> =
        prefs.getStringSet(KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS, DEFAULT_NUMBER_RECOGNITION_PART_MARKERS)
            .orEmpty()
            .let(NumberRecognitionRules::normalizePartMarkers)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_PART_MARKERS }

    fun saveCachedNumberRecognitionPartMarkers(markers: Set<String>) {
        val normalized = NumberRecognitionRules.normalizePartMarkers(markers)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_PART_MARKERS }
        prefs.edit()
            .putStringSet(KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS, normalized)
            .apply()
        NumberRecognitionRules.updatePartMarkers(normalized)
    }

    fun getCustomMgstageSearchPrefixAliases(): Map<String, String> {
        val saved = prefs.getString(KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES, null)
            ?.let { parseMgstageSearchPrefixAliases(it) }
            .orEmpty()
        if (saved.isNotEmpty()) return saved
        return normalizeMgstageSearchPrefixAliases(
            prefs.getStringSet(KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES, emptySet())
                .orEmpty()
                .mapNotNull { it.normalizedMgstageNumberPrefixOrNull() }
                .associateWith { it }
        )
    }

    fun saveCustomMgstageSearchPrefixAliases(aliases: Map<String, String>) {
        val normalized = normalizeMgstageSearchPrefixAliases(aliases)
        prefs.edit()
            .putString(KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES, normalized.toJsonObjectString())
            .remove(KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES)
            .apply()
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
    }

    fun addCustomMgstageNumberPrefix(prefix: String, numericPrefix: String = "") {
        val normalized = prefix.normalizedMgstageNumberPrefixOrNull() ?: return
        val numeric = numericPrefix.filter(Char::isDigit)
        saveCustomMgstageSearchPrefixAliases(
            getCustomMgstageSearchPrefixAliases() + (normalized to "$numeric$normalized")
        )
    }

    fun removeCustomMgstageNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedMgstageNumberPrefixOrNull() ?: return
        saveCustomMgstageSearchPrefixAliases(getCustomMgstageSearchPrefixAliases() - normalized)
    }

    fun getCustomMgstageNumberPrefixes(): Set<String> = getCustomMgstageSearchPrefixAliases().keys

    fun getMergedMgstageNumberPrefixes(): Set<String> =
        getCachedMgstageNumberPrefixes() + getCustomMgstageNumberPrefixes()

    fun getMergedMgstageSearchPrefixAliases(): Map<String, String> =
        getCachedMgstageSearchPrefixAliases() + getCustomMgstageSearchPrefixAliases()

    fun getCustomMgstagePrefixNumberMappings(): Map<String, String> =
        getCustomMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun getCachedMgstagePrefixNumberMappings(): Map<String, String> =
        getCachedMgstageNumberPrefixes().associateWith { "" } +
            getCachedMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun getMergedMgstagePrefixNumberMappings(): Map<String, String> =
        getMergedMgstageNumberPrefixes().associateWith { "" } +
            getMergedMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun saveCustomMgstagePrefixNumberMappings(mappings: Map<String, String>) {
        saveCustomMgstageSearchPrefixAliases(
            mappings.mapNotNull { (prefix, numericPrefix) ->
                val normalizedPrefix = prefix.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                normalizedPrefix to "${numericPrefix.filter(Char::isDigit)}$normalizedPrefix"
            }.toMap()
        )
    }

    fun getRemoteScrapeConfigLastFetchMillis(): Long =
        prefs.getLong(KEY_REMOTE_SCRAPE_CONFIG_LAST_FETCH_MILLIS, 0L)

    fun saveRemoteScrapeConfigLastFetchMillis(value: Long) {
        prefs.edit().putLong(KEY_REMOTE_SCRAPE_CONFIG_LAST_FETCH_MILLIS, value.coerceAtLeast(0L)).apply()
    }

    fun getHomeSortOptionName(): String? = prefs.getString(KEY_HOME_SORT_OPTION, null)

    fun saveHomeSortOptionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_OPTION, value).apply()
    }

    fun getHomeSortDirectionName(): String? = prefs.getString(KEY_HOME_SORT_DIRECTION, null)

    fun saveHomeSortDirectionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_DIRECTION, value).apply()
    }

    fun getCloudSortOptionName(): String? = prefs.getString(KEY_CLOUD_SORT_OPTION, null)

    fun saveCloudSortOptionName(value: String) {
        prefs.edit().putString(KEY_CLOUD_SORT_OPTION, value).apply()
    }

    fun isCloudSortAscending(): Boolean = prefs.getBoolean(KEY_CLOUD_SORT_ASCENDING, false)

    fun saveCloudSortAscending(ascending: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_SORT_ASCENDING, ascending).apply()
    }

    fun getHomeImageModeName(): String? = prefs.getString(KEY_HOME_IMAGE_MODE, null)

    fun saveHomeImageModeName(value: String) {
        prefs.edit().putString(KEY_HOME_IMAGE_MODE, value).apply()
    }

    fun getDomesticRootCid(): Long? =
        prefs.getString(KEY_DOMESTIC_ROOT_CID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()

    fun getDomesticRootCidText(): String =
        getDomesticRootCid()?.toString().orEmpty()

    fun saveDomesticRootCid(value: String) {
        prefs.edit().putString(KEY_DOMESTIC_ROOT_CID, value.filter { it.isDigit() }).apply()
    }

    fun isDomesticPageEnabled(): Boolean =
        prefs.getBoolean(KEY_DOMESTIC_PAGE_ENABLED, false)

    fun saveDomesticPageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOMESTIC_PAGE_ENABLED, enabled).apply()
    }

    fun isLibraryNoMediaEnabled(): Boolean =
        prefs.getBoolean(KEY_LIBRARY_NOMEDIA_ENABLED, true)

    fun saveLibraryNoMediaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIBRARY_NOMEDIA_ENABLED, enabled).apply()
        syncNoMediaForCurrentLibraryRoot()
    }

    fun syncNoMediaForCurrentLibraryRoot() {
        syncNoMediaForLibraryRoot(getLibraryRootUri())
    }

    private fun syncNoMediaForLibraryRoot(uriString: String?) {
        val root = uriString
            ?.let { Uri.parse(it) }
            ?.let { DocumentFile.fromTreeUri(appContext, it) }
            ?: return
        if (isLibraryNoMediaEnabled()) {
            val noMedia = root.findFile(NOMEDIA_FILE_NAME) ?: createNoMediaFile(root)
            noMedia?.uri?.let { uri ->
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(ByteArray(0))
                }
            }
        } else {
            root.findFile(NOMEDIA_FILE_NAME)?.delete()
            root.findFile(NOMEDIA_TEMP_FILE_NAME)?.delete()
        }
    }

    private fun createNoMediaFile(root: DocumentFile): DocumentFile? {
        root.createFile("application/octet-stream", NOMEDIA_FILE_NAME)
            ?.let { created ->
                if (created.name == NOMEDIA_FILE_NAME) return created
                if (created.renameTo(NOMEDIA_FILE_NAME)) {
                    return root.findFile(NOMEDIA_FILE_NAME) ?: created
                }
                created.delete()
            }

        root.findFile(NOMEDIA_TEMP_FILE_NAME)?.delete()
        val temp = root.createFile("application/octet-stream", NOMEDIA_TEMP_FILE_NAME) ?: return null
        return if (temp.renameTo(NOMEDIA_FILE_NAME)) {
            root.findFile(NOMEDIA_FILE_NAME) ?: temp
        } else {
            temp.delete()
            null
        }
    }

    fun isCloudAddButtonMessageEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED, true)

    fun saveCloudAddButtonMessageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED, enabled).apply()
    }

    fun isCloudDeleteEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_DELETE_ENABLED, false)

    fun saveCloudDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_DELETE_ENABLED, enabled).apply()
    }

    fun getCloudExcludedVideoNames(): Set<String> =
        (prefs.getStringSet(KEY_CLOUD_EXCLUDED_VIDEO_NAMES, null) ?: DEFAULT_CLOUD_EXCLUDED_VIDEO_NAMES)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun saveCloudExcludedVideoNames(names: Set<String>) {
        prefs.edit()
            .putStringSet(
                KEY_CLOUD_EXCLUDED_VIDEO_NAMES,
                names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            )
            .apply()
    }

    fun addCloudExcludedVideoName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return
        saveCloudExcludedVideoNames(getCloudExcludedVideoNames() + cleaned)
    }

    fun removeCloudExcludedVideoName(name: String) {
        saveCloudExcludedVideoNames(getCloudExcludedVideoNames() - name)
    }

    fun getCloudScrapeSkipBelowSizeMb(): Int =
        prefs.getInt(KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB, DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
            .coerceIn(0, MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)

    fun saveCloudScrapeSkipBelowSizeMb(value: Int) {
        prefs.edit()
            .putInt(KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB, value.coerceIn(0, MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB))
            .apply()
    }

    fun getCloudScrapeSkipBelowSizeBytes(): Long =
        getCloudScrapeSkipBelowSizeMb().toLong() * 1024L * 1024L

    fun getPlayerSeekBackSeconds(): Int =
        prefs.getInt(KEY_PLAYER_SEEK_BACK_SECONDS, DEFAULT_PLAYER_SEEK_SECONDS)
            .coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS)

    fun savePlayerSeekBackSeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_PLAYER_SEEK_BACK_SECONDS, value.coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS))
            .apply()
    }

    fun getPlayerSeekForwardSeconds(): Int =
        prefs.getInt(KEY_PLAYER_SEEK_FORWARD_SECONDS, DEFAULT_PLAYER_SEEK_SECONDS)
            .coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS)

    fun savePlayerSeekForwardSeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_PLAYER_SEEK_FORWARD_SECONDS, value.coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS))
            .apply()
    }

    fun getExternalSubtitleFontSizeSp(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP, DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP, MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP)

    fun saveExternalSubtitleFontSizeSp(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP, value.coerceIn(MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP, MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP))
            .apply()
    }

    fun getExternalSubtitleBottomPaddingPercent(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT)

    fun saveExternalSubtitleBottomPaddingPercent(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, value.coerceIn(MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT))
            .apply()
    }

    fun getExternalSubtitleBackgroundAlphaPercent(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT)

    fun saveExternalSubtitleBackgroundAlphaPercent(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, value.coerceIn(MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT))
            .apply()
    }

    fun isExternalSubtitleEnabled(mediaKey: String): Boolean =
        prefs.getBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), false)

    fun saveExternalSubtitleEnabled(mediaKey: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), enabled)
            .apply()
    }

    fun getPreferredExternalSubtitleName(mediaKey: String): String =
        prefs.getString(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_NAME_PREFIX, mediaKey), null).orEmpty()

    fun savePreferredExternalSubtitle(mediaKey: String, subtitleName: String, enabled: Boolean = true) {
        prefs.edit()
            .putString(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_NAME_PREFIX, mediaKey), subtitleName)
            .putBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), enabled)
            .apply()
    }

    fun getCustomJsonScrapeConfigs(): List<CustomJsonScrapeConfig> {
        val stored = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_CONFIGS, null).orEmpty()
        val parsed = runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toCustomJsonScrapeConfig()
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { listOf(getLegacyCustomJsonScrapeConfig()) }
    }

    fun getSelectedCustomJsonScrapeConfigIndex(): Int =
        prefs.getInt(KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX, 0)
            .coerceIn(0, (getCustomJsonScrapeConfigs().size - 1).coerceAtLeast(0))

    fun getCustomJsonScrapeConfig(): CustomJsonScrapeConfig {
        val configs = getCustomJsonScrapeConfigs()
        return configs.getOrElse(getSelectedCustomJsonScrapeConfigIndex()) { configs.firstOrNull() ?: CustomJsonScrapeConfig() }
    }

    private fun getLegacyCustomJsonScrapeConfig(): CustomJsonScrapeConfig =
        CustomJsonScrapeConfig(
            enabled = prefs.getBoolean(KEY_CUSTOM_JSON_SCRAPE_ENABLED, false),
            name = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_NAME, null).orEmpty().ifBlank { "自定义 JSON" },
            urlTemplate = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE, null).orEmpty(),
            sampleNumber = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER, null).orEmpty().ifBlank { "SSIS-115" },
            resultPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH, null).orEmpty().ifBlank { "$" },
            numberPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH, null).orEmpty().ifBlank { "$.number" },
            titlePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH, null).orEmpty().ifBlank { "$.title" },
            originalTitlePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH, null).orEmpty(),
            plotPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH, null).orEmpty().ifBlank { "$.plot" },
            premieredPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH, null).orEmpty().ifBlank { "$.premiered" },
            runtimePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH, null).orEmpty().ifBlank { "$.runtime" },
            studioPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH, null).orEmpty().ifBlank { "$.studio" },
            seriesPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH, null).orEmpty().ifBlank { "$.series" },
            actorsPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH, null).orEmpty().ifBlank { "$.actors" },
            genresPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH, null).orEmpty().ifBlank { "$.genres" },
            tagsPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH, null).orEmpty().ifBlank { "$.tags" },
            ratingPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RATING_PATH, null).orEmpty().ifBlank { "$.rating" },
            posterPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH, null).orEmpty().ifBlank { "$.poster" },
            thumbPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH, null).orEmpty().ifBlank { "$.thumb" },
            fanartPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_FANART_PATH, null).orEmpty().ifBlank { "$.fanart" }
        )

    fun saveCustomJsonScrapeConfig(config: CustomJsonScrapeConfig) {
        val configs = getCustomJsonScrapeConfigs().toMutableList()
        val selected = getSelectedCustomJsonScrapeConfigIndex().coerceIn(0, (configs.size - 1).coerceAtLeast(0))
        if (configs.isEmpty()) configs += config else configs[selected] = config
        saveCustomJsonScrapeConfigs(configs, selected)
    }

    fun saveCustomJsonScrapeConfigs(configs: List<CustomJsonScrapeConfig>, selectedIndex: Int) {
        val normalized = configs.ifEmpty { listOf(CustomJsonScrapeConfig(name = "自定义 JSON")) }
        val selected = selectedIndex.coerceIn(0, normalized.lastIndex)
        val selectedConfig = normalized[selected]
        prefs.edit()
            .putString(KEY_CUSTOM_JSON_SCRAPE_CONFIGS, JSONArray(normalized.map { it.toJsonObject() }).toString())
            .putInt(KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX, selected)
            .putLegacyCustomJsonScrapeConfig(selectedConfig)
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.putLegacyCustomJsonScrapeConfig(config: CustomJsonScrapeConfig): android.content.SharedPreferences.Editor =
        putBoolean(KEY_CUSTOM_JSON_SCRAPE_ENABLED, config.enabled)
            .putString(KEY_CUSTOM_JSON_SCRAPE_NAME, config.name.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE, config.urlTemplate.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER, config.sampleNumber.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH, config.resultPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH, config.numberPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH, config.titlePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH, config.originalTitlePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH, config.plotPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH, config.premieredPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH, config.runtimePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH, config.studioPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH, config.seriesPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH, config.actorsPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH, config.genresPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH, config.tagsPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RATING_PATH, config.ratingPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH, config.posterPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH, config.thumbPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_FANART_PATH, config.fanartPath.trim())

    private fun CustomJsonScrapeConfig.toJsonObject(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("name", name)
        put("urlTemplate", urlTemplate)
        put("sampleNumber", sampleNumber)
        put("resultPath", resultPath)
        put("numberPath", numberPath)
        put("titlePath", titlePath)
        put("originalTitlePath", originalTitlePath)
        put("plotPath", plotPath)
        put("premieredPath", premieredPath)
        put("runtimePath", runtimePath)
        put("studioPath", studioPath)
        put("seriesPath", seriesPath)
        put("actorsPath", actorsPath)
        put("genresPath", genresPath)
        put("tagsPath", tagsPath)
        put("ratingPath", ratingPath)
        put("posterPath", posterPath)
        put("thumbPath", thumbPath)
        put("fanartPath", fanartPath)
    }

    private fun JSONObject.toCustomJsonScrapeConfig(): CustomJsonScrapeConfig = CustomJsonScrapeConfig(
        enabled = optBoolean("enabled", false),
        name = optString("name").ifBlank { "自定义 JSON" },
        urlTemplate = optString("urlTemplate"),
        sampleNumber = optString("sampleNumber").ifBlank { "SSIS-115" },
        resultPath = optString("resultPath").ifBlank { "$" },
        numberPath = optString("numberPath").ifBlank { "$.number" },
        titlePath = optString("titlePath").ifBlank { "$.title" },
        originalTitlePath = optString("originalTitlePath"),
        plotPath = optString("plotPath").ifBlank { "$.plot" },
        premieredPath = optString("premieredPath").ifBlank { "$.premiered" },
        runtimePath = optString("runtimePath").ifBlank { "$.runtime" },
        studioPath = optString("studioPath").ifBlank { "$.studio" },
        seriesPath = optString("seriesPath").ifBlank { "$.series" },
        actorsPath = optString("actorsPath").ifBlank { "$.actors" },
        genresPath = optString("genresPath").ifBlank { "$.genres" },
        tagsPath = optString("tagsPath").ifBlank { "$.tags" },
        ratingPath = optString("ratingPath").ifBlank { "$.rating" },
        posterPath = optString("posterPath").ifBlank { "$.poster" },
        thumbPath = optString("thumbPath").ifBlank { "$.thumb" },
        fanartPath = optString("fanartPath").ifBlank { "$.fanart" },
    )

    fun getExternalSubtitleOffsetMs(mediaKey: String, subtitleKey: String): Long =
        prefs.getLong(externalSubtitleOffsetKey(mediaKey, subtitleKey), 0L)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_OFFSET_MS, MAX_EXTERNAL_SUBTITLE_OFFSET_MS)

    fun saveExternalSubtitleOffsetMs(mediaKey: String, subtitleKey: String, offsetMs: Long) {
        val key = externalSubtitleOffsetKey(mediaKey, subtitleKey)
        val normalized = offsetMs.coerceIn(MIN_EXTERNAL_SUBTITLE_OFFSET_MS, MAX_EXTERNAL_SUBTITLE_OFFSET_MS)
        prefs.edit().apply {
            if (normalized == 0L) {
                remove(key)
            } else {
                putLong(key, normalized)
            }
        }.apply()
    }

    private fun externalSubtitleKey(prefix: String, mediaKey: String): String =
        prefix + mediaKey.sha256()

    private fun externalSubtitleOffsetKey(mediaKey: String, subtitleKey: String): String =
        KEY_EXTERNAL_SUBTITLE_OFFSET_PREFIX + "$mediaKey|$subtitleKey".sha256()

    private fun normalizeUpdateProxyBaseUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.trimEnd('/') + "/"
    }

    private fun String.normalizedNumberPrefixOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() }

    private fun String.normalizedMgstageNumberPrefixOrNull(): String? =
        normalizedNumberPrefixOrNull()
            ?.takeIf { value -> value.any { it.isLetter() } }

    private fun parseMgstageSearchPrefixAliases(jsonText: String): Map<String, String>? =
        runCatching {
            val json = JSONObject(jsonText)
            normalizeMgstageSearchPrefixAliases(
                json.keys().asSequence()
                .mapNotNull { key ->
                    val normalizedKey = key.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                    val normalizedValue = json.optString(key).normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                    normalizedKey to normalizedValue
                }
                .toMap()
            )
        }.getOrNull()

    private fun normalizeMgstageSearchPrefixAliases(aliases: Map<String, String>): Map<String, String> =
        aliases.mapNotNull { (key, value) ->
            val normalizedKey = key.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
            val normalizedValue = value.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
            val numericPrefix = normalizedValue.takeWhile(Char::isDigit)
            val valuePrefix = normalizedValue.drop(numericPrefix.length)
                .normalizedMgstageNumberPrefixOrNull()
            val canonicalPrefix = if (numericPrefix.isNotEmpty() && valuePrefix != null) {
                valuePrefix
            } else {
                normalizedKey
            }
            canonicalPrefix to if (numericPrefix.isEmpty()) canonicalPrefix else "$numericPrefix$canonicalPrefix"
        }.toMap()

    private fun Map<String, String>.toJsonObjectString(): String {
        val json = JSONObject()
        forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    private fun Map<String, String>.toPrefixNumberMappings(): Map<String, String> =
        mapValues { (prefix, searchPrefix) ->
            searchPrefix.removeSuffix(prefix).filter(Char::isDigit)
        }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
        const val KEY_AVSUBTITLES_COOKIES = "avsubtitles_cookies"
        const val KEY_SUBTITLE_SEARCH_PROVIDER = "subtitle_search_provider"
        const val KEY_CLOUD115_LOGIN_APP = "cloud115_login_app"
        const val KEY_UPDATE_MANIFEST_URL = "update_manifest_url"
        const val DEFAULT_UPDATE_MANIFEST_URL =
            "https://github.com/water2100/HomeMovie/releases/latest/download/latest.json"
        const val KEY_UPDATE_PROXY_BASE_URL = "update_proxy_base_url"
        const val DEFAULT_UPDATE_PROXY_BASE_URL = "https://v4.gh-proxy.org/"
        const val KEY_UPDATE_PROXY_ENABLED = "update_proxy_enabled"
        const val KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED = "update_auto_check_on_startup_enabled"
        const val KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED = "update_auto_delete_installed_apk_enabled"
        const val KEY_STARTUP_ANIMATION_ENABLED = "startup_animation_enabled"
        const val KEY_STARTUP_ANIMATION_IMAGE_URI = "startup_animation_image_uri"
        const val KEY_PENDING_UPDATE_INSTALL_VERSION_CODE = "pending_update_install_version_code"
        const val KEY_PENDING_UPDATE_INSTALL_APK_PATH = "pending_update_install_apk_path"
        const val KEY_DEFAULT_SCRAPE_SOURCE = "default_scrape_source"
        const val KEY_PRIORITY_SCRAPE_SOURCES = "priority_scrape_sources"
        const val KEY_IMAGE_DOWNLOAD_RETRY_COUNT = "image_download_retry_count"
        const val KEY_SCRAPE_CONCURRENCY_LIMIT = "scrape_concurrency_limit"
        const val KEY_SCRAPE_PROXY_ADDRESS = "scrape_proxy_address"
        const val KEY_SCRAPE_PROXY_ENABLED = "scrape_proxy_enabled"
        const val KEY_DMM2_SKIPPED_NUMBER_PREFIXES = "dmm2_skipped_number_prefixes"
        const val KEY_REMOTE_SCRAPE_CONFIG_URL = "remote_scrape_config_url"
        const val DEFAULT_REMOTE_SCRAPE_CONFIG_URL =
            "https://github.com/water2100/HomeMovie/releases/latest/download/scrape-config.json"
        const val KEY_CACHED_MGSTAGE_NUMBER_PREFIXES = "cached_mgstage_number_prefixes"
        const val KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES = "cached_mgstage_search_prefix_aliases"
        const val KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES = "cached_number_recognition_ignored_suffixes"
        const val KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS = "cached_number_recognition_part_markers"
        const val KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES = "custom_mgstage_number_prefixes"
        const val KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES = "custom_mgstage_search_prefix_aliases"
        const val KEY_REMOTE_SCRAPE_CONFIG_LAST_FETCH_MILLIS = "remote_scrape_config_last_fetch_millis"
        const val KEY_HOME_SORT_OPTION = "home_sort_option"
        const val KEY_HOME_SORT_DIRECTION = "home_sort_direction"
        const val KEY_CLOUD_SORT_OPTION = "cloud_sort_option"
        const val KEY_CLOUD_SORT_ASCENDING = "cloud_sort_ascending"
        const val KEY_PLAYER_SEEK_BACK_SECONDS = "player_seek_back_seconds"
        const val KEY_PLAYER_SEEK_FORWARD_SECONDS = "player_seek_forward_seconds"
        const val KEY_HOME_IMAGE_MODE = "home_image_mode"
        const val KEY_DOMESTIC_ROOT_CID = "domestic_root_cid"
        const val KEY_DOMESTIC_PAGE_ENABLED = "domestic_page_enabled"
        const val KEY_LIBRARY_NOMEDIA_ENABLED = "library_nomedia_enabled"
        const val KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED = "cloud_add_button_message_enabled"
        const val KEY_CLOUD_DELETE_ENABLED = "cloud_delete_enabled"
        const val KEY_CLOUD_EXCLUDED_VIDEO_NAMES = "cloud_excluded_video_names"
        const val KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = "cloud_scrape_skip_below_size_mb"
        const val KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP = "external_subtitle_font_size_sp"
        const val KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = "external_subtitle_bottom_padding_percent"
        const val KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = "external_subtitle_background_alpha_percent"
        const val KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX = "external_subtitle_enabled_"
        const val KEY_EXTERNAL_SUBTITLE_NAME_PREFIX = "external_subtitle_name_"
        const val KEY_EXTERNAL_SUBTITLE_OFFSET_PREFIX = "external_subtitle_offset_"
        const val KEY_CUSTOM_JSON_SCRAPE_CONFIGS = "custom_json_scrape_configs"
        const val KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX = "custom_json_scrape_selected_index"
        const val KEY_CUSTOM_JSON_SCRAPE_ENABLED = "custom_json_scrape_enabled"
        const val KEY_CUSTOM_JSON_SCRAPE_NAME = "custom_json_scrape_name"
        const val KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE = "custom_json_scrape_url_template"
        const val KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER = "custom_json_scrape_sample_number"
        const val KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH = "custom_json_scrape_result_path"
        const val KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH = "custom_json_scrape_number_path"
        const val KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH = "custom_json_scrape_title_path"
        const val KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH = "custom_json_scrape_original_title_path"
        const val KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH = "custom_json_scrape_plot_path"
        const val KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH = "custom_json_scrape_premiered_path"
        const val KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH = "custom_json_scrape_runtime_path"
        const val KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH = "custom_json_scrape_studio_path"
        const val KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH = "custom_json_scrape_series_path"
        const val KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH = "custom_json_scrape_actors_path"
        const val KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH = "custom_json_scrape_genres_path"
        const val KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH = "custom_json_scrape_tags_path"
        const val KEY_CUSTOM_JSON_SCRAPE_RATING_PATH = "custom_json_scrape_rating_path"
        const val KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH = "custom_json_scrape_poster_path"
        const val KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH = "custom_json_scrape_thumb_path"
        const val KEY_CUSTOM_JSON_SCRAPE_FANART_PATH = "custom_json_scrape_fanart_path"
        const val DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT = 5
        const val DEFAULT_SCRAPE_CONCURRENCY_LIMIT = 2
        const val MAX_SCRAPE_CONCURRENCY_LIMIT = 4
        const val DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 100
        const val MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 102400
        val PRIORITY_SCRAPE_SOURCE_OPTIONS = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Official,
            ScrapeSource.Mgstage,
            ScrapeSource.Javbus,
            ScrapeSource.TheJavDB,
            ScrapeSource.CustomJson
        )
        val DEFAULT_PRIORITY_SCRAPE_SOURCES = listOf(ScrapeSource.Dmm2, ScrapeSource.TheJavDB, ScrapeSource.Javbus)
        private val OLD_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION =
            listOf(ScrapeSource.Dmm2, ScrapeSource.Javbus, ScrapeSource.TheJavDB)
        private val OLD_DMM_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION =
            listOf(ScrapeSource.Dmm2, ScrapeSource.Javbus)
        val DEFAULT_DMM2_SKIPPED_NUMBER_PREFIXES = setOf("ABF", "ABW", "ABP", "REBDB", "TRE", "PPT", "CHN", "BGN")
        val DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES = mapOf(
            "SHN" to "116SHN",
            "DANDY" to "104DANDY",
            "GANA" to "200GANA",
            "SCUTE" to "229SCUTE",
            "LUXU" to "259LUXU",
            "ARA" to "261ARA",
            "DCV" to "277DCV",
            "MY" to "292MY",
            "EWDX" to "299EWDX",
            "MAAN" to "300MAAN",
            "MIUM" to "300MIUM",
            "NTK" to "300NTK",
            "KIRAY" to "314KIRAY",
            "KJO" to "326KJO",
            "NAMA" to "332NAMA",
            "KNB" to "336KNB",
            "SIMM" to "345SIMM",
            "NTR" to "348NTR",
            "ICHK" to "368ICHK",
            "JAC" to "390JAC",
            "KIWVR" to "408KIWVR",
            "INST" to "413INST",
            "SRYA" to "417SRYA",
            "SUKE" to "428SUKE",
            "MFC" to "435MFC",
            "HHH" to "451HHH",
            "TEN" to "459TEN",
            "MLA" to "476MLA",
            "SGK" to "483SGK",
            "GCB" to "485GCB",
            "SEI" to "502SEI",
            "STCV" to "529STCV"
        )
        val DEFAULT_MGSTAGE_NUMBER_PREFIXES =
            DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES.keys + setOf("SIRO")
        val DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES = NumberRecognitionRules.DEFAULT_IGNORED_SUFFIXES
        val DEFAULT_NUMBER_RECOGNITION_PART_MARKERS = NumberRecognitionRules.DEFAULT_PART_MARKERS
        const val DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 22
        const val DEFAULT_PLAYER_SEEK_SECONDS = 10
        const val MIN_PLAYER_SEEK_SECONDS = 1
        const val MAX_PLAYER_SEEK_SECONDS = 120
        const val MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 14
        const val MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 40
        const val DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 8
        const val MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 30
        const val DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 80
        const val MIN_EXTERNAL_SUBTITLE_OFFSET_MS = -600_000L
        const val MAX_EXTERNAL_SUBTITLE_OFFSET_MS = 600_000L
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val NOMEDIA_TEMP_FILE_NAME = "nomedia.tmp"
        val DEFAULT_CLOUD_EXCLUDED_VIDEO_NAMES = setOf(
            "18+游戏大全(996gg.cc)-七龍珠H版-三國志H版-三國群淫傳等.mp4",
            "美女直播.mp4",
            "manko.fun.mp4",
            "嫑嫑聯盟視頻文宣biao88.net.mp4",
            "美女荷官自拍被干vip447.mp4",
            "x u u 6 2 . c o m.mp4",
            "澳门威尼斯人注册免费送48元.mp4",
            "澳门银河赌场-注册免费送36元 可提款-.mp4",
            "裸聊直播,可以指揮潮吹表演 YYAA2.COM.mp4",
            "新 片 首 發 每 天 更 新 同 步 日 韓.mp4",
            "精彩片段.mp4",
            "uur76.mp4",
            "N房间的精彩直播 只有你想不到的刺激 UUS75.COM.mp4",
            "妹妹在精彩表演 ———-哥哥快来大饱眼福uuf39.com.mp4",
            "女神在线视频www.55h.me.mp4",
            "最新情报.mp4",
            "台湾uu祼聊室，注册成为会员免费送50点，指挥妹子露波露B.mp4",
            "乐鱼体育-ya116.com官方指定欧洲杯下注的网站.mp4",
            "有趣的小视频.mp4",
            "苍老师强力推荐.mp4"
        )
    }
}
