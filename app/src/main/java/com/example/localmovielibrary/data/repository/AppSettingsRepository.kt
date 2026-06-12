package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115CookieProvider
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.scraper.MissavScrapeLanguage
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.util.NumberRecognitionRules
import org.json.JSONObject
import java.security.MessageDigest

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

    fun getMissavCookies(): String = prefs.getString(KEY_MISSAV_COOKIES, null).orEmpty()

    fun saveMissavCookies(value: String) {
        prefs.edit().putString(KEY_MISSAV_COOKIES, value.trim()).commit()
    }

    fun getMissavScrapeLanguage(): MissavScrapeLanguage =
        MissavScrapeLanguage.fromId(prefs.getString(KEY_MISSAV_SCRAPE_LANGUAGE, null))

    fun saveMissavScrapeLanguage(language: MissavScrapeLanguage) {
        prefs.edit().putString(KEY_MISSAV_SCRAPE_LANGUAGE, language.id).apply()
    }

    fun getJavzimuCookies(): String = prefs.getString(KEY_JAVZIMU_COOKIES, null).orEmpty()

    fun saveJavzimuCookies(value: String) {
        prefs.edit().putString(KEY_JAVZIMU_COOKIES, value.trim()).commit()
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
                ScrapeSource.entries.firstOrNull { it.name == name.trim() }
            }
            ?.filter { it in PRIORITY_SCRAPE_SOURCE_OPTIONS }
            ?.distinct()
            .orEmpty()
        return stored.ifEmpty { DEFAULT_PRIORITY_SCRAPE_SOURCES }
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

    private fun externalSubtitleKey(prefix: String, mediaKey: String): String =
        prefix + mediaKey.sha256()

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
        const val KEY_MISSAV_COOKIES = "missav_cookies"
        const val KEY_MISSAV_SCRAPE_LANGUAGE = "missav_scrape_language"
        const val KEY_JAVZIMU_COOKIES = "javzimu_cookies"
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
        const val KEY_PENDING_UPDATE_INSTALL_VERSION_CODE = "pending_update_install_version_code"
        const val KEY_PENDING_UPDATE_INSTALL_APK_PATH = "pending_update_install_apk_path"
        const val KEY_DEFAULT_SCRAPE_SOURCE = "default_scrape_source"
        const val KEY_PRIORITY_SCRAPE_SOURCES = "priority_scrape_sources"
        const val KEY_IMAGE_DOWNLOAD_RETRY_COUNT = "image_download_retry_count"
        const val KEY_SCRAPE_CONCURRENCY_LIMIT = "scrape_concurrency_limit"
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
        const val KEY_HOME_IMAGE_MODE = "home_image_mode"
        const val KEY_DOMESTIC_ROOT_CID = "domestic_root_cid"
        const val KEY_DOMESTIC_PAGE_ENABLED = "domestic_page_enabled"
        const val KEY_LIBRARY_NOMEDIA_ENABLED = "library_nomedia_enabled"
        const val KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED = "cloud_add_button_message_enabled"
        const val KEY_CLOUD_EXCLUDED_VIDEO_NAMES = "cloud_excluded_video_names"
        const val KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = "cloud_scrape_skip_below_size_mb"
        const val KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP = "external_subtitle_font_size_sp"
        const val KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = "external_subtitle_bottom_padding_percent"
        const val KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = "external_subtitle_background_alpha_percent"
        const val KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX = "external_subtitle_enabled_"
        const val KEY_EXTERNAL_SUBTITLE_NAME_PREFIX = "external_subtitle_name_"
        const val DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT = 5
        const val DEFAULT_SCRAPE_CONCURRENCY_LIMIT = 2
        const val MAX_SCRAPE_CONCURRENCY_LIMIT = 4
        const val DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 100
        const val MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 102400
        val PRIORITY_SCRAPE_SOURCE_OPTIONS = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Dmm,
            ScrapeSource.Official,
            ScrapeSource.Mgstage,
            ScrapeSource.Javbus,
            ScrapeSource.Javdb,
            ScrapeSource.Missav
        )
        val DEFAULT_PRIORITY_SCRAPE_SOURCES = listOf(ScrapeSource.Dmm2, ScrapeSource.Dmm, ScrapeSource.Javbus, ScrapeSource.Javdb)
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
        const val MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 14
        const val MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 40
        const val DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 8
        const val MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 30
        const val DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 80
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
