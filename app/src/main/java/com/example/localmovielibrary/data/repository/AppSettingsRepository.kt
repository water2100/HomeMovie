package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115CookieProvider
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.translate.DeepSeekPromptTemplates
import com.example.localmovielibrary.translate.TranslateProvider

class AppSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

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
        syncNoMediaForLibraryRoot(uriString)
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
        return ScrapeSource.entries.firstOrNull { it.name == stored } ?: ScrapeSource.Dmm2
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

    fun getScrapeConcurrencyLimit(): Int {
        return prefs.getInt(KEY_SCRAPE_CONCURRENCY_LIMIT, DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
            .coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)
    }

    fun saveScrapeConcurrencyLimit(count: Int) {
        prefs.edit().putInt(KEY_SCRAPE_CONCURRENCY_LIMIT, count.coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)).apply()
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

    fun getTranslateProvider(): TranslateProvider =
        TranslateProvider.fromId(prefs.getString(KEY_TRANSLATE_PROVIDER, null))

    fun saveTranslateProvider(provider: TranslateProvider) {
        prefs.edit().putString(KEY_TRANSLATE_PROVIDER, provider.id).apply()
    }

    fun getDeepSeekApiKey(): String =
        prefs.getString(KEY_DEEPSEEK_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    fun saveDeepSeekApiKey(value: String) {
        prefs.edit().putString(KEY_DEEPSEEK_API_KEY, value.trim()).apply()
    }

    fun getDeepSeekBaseUrl(): String =
        prefs.getString(KEY_DEEPSEEK_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEEPSEEK_BASE_URL

    fun saveDeepSeekBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/').ifBlank { DEFAULT_DEEPSEEK_BASE_URL }
        prefs.edit().putString(KEY_DEEPSEEK_BASE_URL, normalized).apply()
    }

    fun getDeepSeekModel(): String =
        prefs.getString(KEY_DEEPSEEK_MODEL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEEPSEEK_MODEL

    fun saveDeepSeekModel(value: String) {
        prefs.edit().putString(KEY_DEEPSEEK_MODEL, value.trim().ifBlank { DEFAULT_DEEPSEEK_MODEL }).apply()
    }

    fun isDeepSeekThinkingEnabled(): Boolean =
        prefs.getBoolean(KEY_DEEPSEEK_THINKING_ENABLED, false)

    fun saveDeepSeekThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPSEEK_THINKING_ENABLED, enabled).apply()
    }

    fun isDeepSeekPromptEnabled(): Boolean =
        prefs.getBoolean(KEY_DEEPSEEK_PROMPT_ENABLED, true)

    fun saveDeepSeekPromptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPSEEK_PROMPT_ENABLED, enabled).apply()
    }

    fun getDeepSeekPromptTemplateId(): String =
        DeepSeekPromptTemplates.find(prefs.getString(KEY_DEEPSEEK_PROMPT_TEMPLATE_ID, null)).id

    fun saveDeepSeekPromptTemplateId(value: String) {
        prefs.edit().putString(KEY_DEEPSEEK_PROMPT_TEMPLATE_ID, DeepSeekPromptTemplates.find(value).id).apply()
    }

    fun getDeepSeekCustomPrompt(): String =
        prefs.getString(KEY_DEEPSEEK_CUSTOM_PROMPT, null).orEmpty()

    fun saveDeepSeekCustomPrompt(value: String) {
        prefs.edit().putString(KEY_DEEPSEEK_CUSTOM_PROMPT, value.trim()).apply()
    }

    fun getDeepSeekTranslatePrompt(): String {
        val template = DeepSeekPromptTemplates.find(getDeepSeekPromptTemplateId())
        if (template.id == DeepSeekPromptTemplates.CUSTOM_ID) {
            return getDeepSeekCustomPrompt().ifBlank { DEFAULT_DEEPSEEK_TRANSLATE_PROMPT }
        }
        val assetPath = template.assetPath ?: return DEFAULT_DEEPSEEK_TRANSLATE_PROMPT
        return runCatching {
            appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEEPSEEK_TRANSLATE_PROMPT
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

    fun getAsrModelId(): String =
        prefs.getString(KEY_ASR_MODEL_ID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ASR_MODEL_ID

    fun saveAsrModelId(value: String) {
        prefs.edit().putString(KEY_ASR_MODEL_ID, value.trim().ifBlank { DEFAULT_ASR_MODEL_ID }).apply()
    }

    fun getAsrModelBaseUrl(): String =
        prefs.getString(KEY_ASR_MODEL_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ASR_MODEL_BASE_URL

    fun saveAsrModelBaseUrl(value: String) {
        prefs.edit().putString(
            KEY_ASR_MODEL_BASE_URL,
            value.trim().trimEnd('/').ifBlank { DEFAULT_ASR_MODEL_BASE_URL }
        ).apply()
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

    fun isPlayerLiveSubtitleEnabled(): Boolean =
        prefs.getBoolean(KEY_PLAYER_LIVE_SUBTITLE_ENABLED, false)

    fun savePlayerLiveSubtitleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_LIVE_SUBTITLE_ENABLED, enabled).apply()
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
        const val KEY_JAVZIMU_COOKIES = "javzimu_cookies"
        const val KEY_AVSUBTITLES_COOKIES = "avsubtitles_cookies"
        const val KEY_SUBTITLE_SEARCH_PROVIDER = "subtitle_search_provider"
        const val KEY_CLOUD115_LOGIN_APP = "cloud115_login_app"
        const val KEY_DEFAULT_SCRAPE_SOURCE = "default_scrape_source"
        const val KEY_IMAGE_DOWNLOAD_RETRY_COUNT = "image_download_retry_count"
        const val KEY_SCRAPE_CONCURRENCY_LIMIT = "scrape_concurrency_limit"
        const val KEY_HOME_SORT_OPTION = "home_sort_option"
        const val KEY_HOME_SORT_DIRECTION = "home_sort_direction"
        const val KEY_HOME_IMAGE_MODE = "home_image_mode"
        const val KEY_BAIDU_TRANSLATE_APP_ID = "baidu_translate_app_id"
        const val KEY_BAIDU_TRANSLATE_SECRET_KEY = "baidu_translate_secret_key"
        const val KEY_TRANSLATE_PROVIDER = "translate_provider"
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_DEEPSEEK_BASE_URL = "deepseek_base_url"
        const val KEY_DEEPSEEK_MODEL = "deepseek_model"
        const val KEY_DEEPSEEK_THINKING_ENABLED = "deepseek_thinking_enabled"
        const val KEY_DEEPSEEK_PROMPT_ENABLED = "deepseek_prompt_enabled"
        const val KEY_DEEPSEEK_PROMPT_TEMPLATE_ID = "deepseek_prompt_template_id"
        const val KEY_DEEPSEEK_CUSTOM_PROMPT = "deepseek_custom_prompt"
        const val KEY_DOMESTIC_ROOT_CID = "domestic_root_cid"
        const val KEY_LIBRARY_NOMEDIA_ENABLED = "library_nomedia_enabled"
        const val KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED = "cloud_add_button_message_enabled"
        const val KEY_CLOUD_EXCLUDED_VIDEO_NAMES = "cloud_excluded_video_names"
        const val KEY_ASR_MODEL_ID = "asr_model_id"
        const val KEY_ASR_MODEL_BASE_URL = "asr_model_base_url"
        const val KEY_DETAIL_THUMB_BACKGROUND_ENABLED = "detail_thumb_background_enabled"
        const val KEY_DETAIL_THUMB_BACKGROUND_ALPHA = "detail_thumb_background_alpha"
        const val KEY_PLAYER_LIVE_SUBTITLE_ENABLED = "player_live_subtitle_enabled"
        const val KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP = "external_subtitle_font_size_sp"
        const val KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = "external_subtitle_bottom_padding_percent"
        const val KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = "external_subtitle_background_alpha_percent"
        const val DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT = 5
        const val DEFAULT_SCRAPE_CONCURRENCY_LIMIT = 2
        const val MAX_SCRAPE_CONCURRENCY_LIMIT = 4
        const val DEFAULT_DETAIL_THUMB_BACKGROUND_ALPHA = 32
        const val DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 22
        const val MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 14
        const val MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 40
        const val DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 8
        const val MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 30
        const val DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 80
        const val DEFAULT_BAIDU_TRANSLATE_APP_ID = ""
        const val DEFAULT_BAIDU_TRANSLATE_SECRET_KEY = ""
        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val DEFAULT_DEEPSEEK_TRANSLATE_PROMPT =
            "你是专业影视字幕翻译引擎。请把输入的日文字幕翻译成自然、简洁、口语化的简体中文。只输出译文，不要解释。"
        const val DEFAULT_ASR_MODEL_ID = "sense-voice-zh-en-ja-ko-yue-int8"
        const val DEFAULT_ASR_MODEL_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
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
