package com.example.localmovielibrary.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.asr.AsrModelManager
import com.example.localmovielibrary.asr.AsrModelOption
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrary.cloud115.Cloud115QrLoginStatus
import com.example.localmovielibrary.cloud115.Cloud115QrToken
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.translate.TranslateProvider
import com.example.localmovielibrary.translate.DeepSeekPromptTemplate
import com.example.localmovielibrary.translate.DeepSeekPromptTemplates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val cloud115QrLoginClient: Cloud115QrLoginClient,
    private val asrModelManager: AsrModelManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    private var qrLoginJob: Job? = null
    private var asrDownloadJob: Job? = null

    fun refreshSavedCloud115Accounts() {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.listSavedAccounts() }
                .onSuccess { accounts ->
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName ?: it.selectedCloud115AccountFileName
                        )
                    }
                }
        }
    }

    fun applySavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.applySavedAccount(account.fileName) }
                .onSuccess { selected ->
                    _uiState.value = loadState().copy(
                        savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                        selectedCloud115AccountFileName = selected.fileName,
                        savedMessage = "已切换 115 账号：${selected.displayName}"
                    )
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "切换 115 账号失败") }
                }
        }
    }

    fun deleteSavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.deleteSavedAccount(account.fileName) }
                .onSuccess { deleted ->
                    val accounts = cloud115QrLoginClient.listSavedAccounts()
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName,
                            savedMessage = if (deleted) {
                                "已删除 115 账号：${account.displayName}"
                            } else {
                                "账号文件不存在，已刷新列表"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "删除 115 账号失败") }
                }
        }
    }

    fun selectCloud115LoginApp(app: Cloud115LoginApp) {
        repository.saveCloud115LoginApp(app.app)
        _uiState.update {
            it.copy(
                selectedCloud115LoginApp = app,
                cloud115QrStatusText = "已选择：${app.description}",
                savedMessage = null
            )
        }
    }

    fun startCloud115QrLogin() {
        qrLoginJob?.cancel()
        val loginApp = _uiState.value.selectedCloud115LoginApp
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = true,
                cloud115QrToken = null,
                cloud115QrStatusText = "正在获取 115 登录二维码...",
                cloud115QrSavedFile = null,
                savedMessage = null
            )
        }
        qrLoginJob = viewModelScope.launch {
            runCatching {
                val token = cloud115QrLoginClient.requestToken()
                _uiState.update {
                    it.copy(
                        cloud115QrToken = token,
                        cloud115QrStatusText = "请使用 115 App 扫码登录：${loginApp.description}"
                    )
                }
                while (true) {
                    delay(QR_LOGIN_POLL_INTERVAL_MS)
                    when (cloud115QrLoginClient.checkStatus(token)) {
                        Cloud115QrLoginStatus.Waiting -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "等待扫码...") }
                        }

                        Cloud115QrLoginStatus.Scanned -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "已扫码，请在手机上确认登录") }
                        }

                        Cloud115QrLoginStatus.Confirmed -> {
                            val result = cloud115QrLoginClient.login(token, loginApp)
                            _uiState.value = loadState().copy(
                                savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                                selectedCloud115AccountFileName = result.fileName,
                                isCloud115QrLoginActive = false,
                                cloud115QrToken = null,
                                cloud115QrStatusText = "115 登录成功，Cookie 文件已保存：${result.fileName}",
                                cloud115QrSavedFile = result.filePath,
                                savedMessage = "115 登录成功，已保存 ${result.fileName}"
                            )
                            return@launch
                        }

                        Cloud115QrLoginStatus.Expired -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "二维码已过期，请重新获取"
                                )
                            }
                            return@launch
                        }

                        Cloud115QrLoginStatus.Canceled -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "扫码登录已取消"
                                )
                            }
                            return@launch
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCloud115QrLoginActive = false,
                        cloud115QrStatusText = error.message ?: "115 二维码登录失败",
                        savedMessage = error.message ?: "115 二维码登录失败"
                    )
                }
            }
        }
    }

    fun cancelCloud115QrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = false,
                cloud115QrToken = null,
                cloud115QrStatusText = "已取消 115 二维码登录"
            )
        }
    }

    fun updateMissavCookies(value: String) {
        _uiState.update { it.copy(missavCookies = value, savedMessage = null) }
    }

    fun saveMissavCookies(value: String) {
        repository.saveMissavCookies(value)
        _uiState.value = loadState().copy(savedMessage = "MissAV Cookie 已保存")
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(strmBaseUrl = value, savedMessage = null) }
    }

    fun updateDefaultScrapeSource(source: ScrapeSource) {
        repository.saveDefaultScrapeSource(source)
        _uiState.update { it.copy(defaultScrapeSource = source, savedMessage = null) }
    }

    fun updateImageDownloadRetryCount(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(imageDownloadRetryCountText = cleaned, savedMessage = null) }
    }

    fun updateScrapeConcurrencyLimit(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(1)
        _uiState.update { it.copy(scrapeConcurrencyLimitText = cleaned, savedMessage = null) }
    }

    fun updateBaiduTranslateAppId(value: String) {
        _uiState.update { it.copy(baiduTranslateAppId = value.trim(), savedMessage = null) }
    }

    fun updateBaiduTranslateSecretKey(value: String) {
        _uiState.update { it.copy(baiduTranslateSecretKey = value.trim(), savedMessage = null) }
    }

    fun updateTranslateProvider(provider: TranslateProvider) {
        repository.saveTranslateProvider(provider)
        _uiState.value = loadState().copy(savedMessage = "翻译服务已切换为：${provider.label}")
    }

    fun updateDeepSeekApiKey(value: String) {
        _uiState.update { it.copy(deepSeekApiKey = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekBaseUrl(value: String) {
        _uiState.update { it.copy(deepSeekBaseUrl = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekModel(value: String) {
        _uiState.update { it.copy(deepSeekModel = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekThinkingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(deepSeekThinkingEnabled = enabled, savedMessage = null) }
    }

    fun updateDeepSeekPromptEnabled(enabled: Boolean) {
        _uiState.update { it.copy(deepSeekPromptEnabled = enabled, savedMessage = null) }
    }

    fun updateDeepSeekPromptTemplate(template: DeepSeekPromptTemplate) {
        _uiState.update {
            it.copy(
                deepSeekPromptTemplateId = template.id,
                savedMessage = null
            )
        }
    }

    fun updateDeepSeekCustomPrompt(value: String) {
        _uiState.update { it.copy(deepSeekCustomPrompt = value, savedMessage = null) }
    }

    fun updateDomesticRootCid(value: String) {
        _uiState.update { it.copy(domesticRootCidText = value.filter { char -> char.isDigit() }, savedMessage = null) }
    }

    fun updateLibraryNoMediaEnabled(enabled: Boolean) {
        repository.saveLibraryNoMediaEnabled(enabled)
        _uiState.update {
            it.copy(
                libraryNoMediaEnabled = enabled,
                savedMessage = if (enabled) {
                    "已开启 .nomedia 屏蔽图片"
                } else {
                    "已关闭 .nomedia 屏蔽图片"
                }
            )
        }
    }

    fun updateCloudAddButtonMessageEnabled(enabled: Boolean) {
        repository.saveCloudAddButtonMessageEnabled(enabled)
        _uiState.update { it.copy(cloudAddButtonMessageEnabled = enabled, savedMessage = null) }
    }

    fun updateNewExcludedVideoName(value: String) {
        _uiState.update { it.copy(newExcludedVideoName = value, savedMessage = null) }
    }

    fun addExcludedVideoName() {
        val name = _uiState.value.newExcludedVideoName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要排除的视频文件名") }
            return
        }
        repository.addCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已添加排除视频：$name")
    }

    fun removeExcludedVideoName(name: String) {
        repository.removeCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已移除排除视频")
    }

    fun updateAsrModel(model: AsrModelOption) {
        repository.saveAsrModelId(model.id)
        _uiState.value = loadState().copy(savedMessage = "ASR 模型已选择：${model.label}")
    }

    fun updateAsrModelBaseUrl(value: String) {
        _uiState.update { it.copy(asrModelBaseUrl = value.trim(), savedMessage = null) }
    }

    fun downloadAsrModel() {
        if (_uiState.value.isAsrModelDownloading) return
        asrDownloadJob?.cancel()
        _uiState.update {
            it.copy(
                isAsrModelDownloading = true,
                asrModelDownloadProgress = 0,
                asrModelDownloadMessage = "准备下载模型...",
                savedMessage = null
            )
        }
        asrDownloadJob = viewModelScope.launch {
            runCatching {
                asrModelManager.downloadCurrentModel { progress, message ->
                    _uiState.update {
                        it.copy(
                            asrModelDownloadProgress = progress,
                            asrModelDownloadMessage = message
                        )
                    }
                }
            }.onSuccess { status ->
                _uiState.value = loadState().copy(
                    isAsrModelDownloading = false,
                    asrModelDownloadProgress = 100,
                    asrModelDownloadMessage = "模型已下载：${formatBytes(status.sizeBytes)}",
                    savedMessage = "ASR 模型下载完成"
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAsrModelDownloading = false,
                        asrModelDownloadMessage = error.message ?: "ASR 模型下载失败",
                        savedMessage = error.message ?: "ASR 模型下载失败"
                    )
                }
            }
        }
    }

    fun saveBaiduTranslateSettings() {
        val state = _uiState.value
        repository.saveTranslateProvider(state.translateProvider)
        repository.saveBaiduTranslateAppId(state.baiduTranslateAppId)
        repository.saveBaiduTranslateSecretKey(state.baiduTranslateSecretKey)
        repository.saveDeepSeekApiKey(state.deepSeekApiKey)
        repository.saveDeepSeekBaseUrl(state.deepSeekBaseUrl)
        repository.saveDeepSeekModel(state.deepSeekModel)
        repository.saveDeepSeekThinkingEnabled(state.deepSeekThinkingEnabled)
        repository.saveDeepSeekPromptEnabled(state.deepSeekPromptEnabled)
        repository.saveDeepSeekPromptTemplateId(state.deepSeekPromptTemplateId)
        repository.saveDeepSeekCustomPrompt(state.deepSeekCustomPrompt)
        repository.saveDomesticRootCid(state.domesticRootCidText)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        _uiState.value = loadState().copy(savedMessage = "翻译配置已保存")
    }

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveMissavCookies(state.missavCookies)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveScrapeConcurrencyLimit(state.scrapeConcurrencyLimitText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
        repository.saveTranslateProvider(state.translateProvider)
        repository.saveBaiduTranslateAppId(state.baiduTranslateAppId)
        repository.saveBaiduTranslateSecretKey(state.baiduTranslateSecretKey)
        repository.saveDeepSeekApiKey(state.deepSeekApiKey)
        repository.saveDeepSeekBaseUrl(state.deepSeekBaseUrl)
        repository.saveDeepSeekModel(state.deepSeekModel)
        repository.saveDeepSeekThinkingEnabled(state.deepSeekThinkingEnabled)
        repository.saveDeepSeekPromptEnabled(state.deepSeekPromptEnabled)
        repository.saveDeepSeekPromptTemplateId(state.deepSeekPromptTemplateId)
        repository.saveDeepSeekCustomPrompt(state.deepSeekCustomPrompt)
        repository.saveDomesticRootCid(state.domesticRootCidText)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        _uiState.value = loadState().copy(savedMessage = "设置已保存")
    }

    fun saveStrmDirectory(uri: Uri) {
        repository.saveStrmTreeUri(uri)
        _uiState.value = loadState().copy(savedMessage = "STRM 保存位置已更新")
    }

    fun scanLibrary(uri: Uri) {
        repository.saveLibraryRootUri(uri)
        _uiState.update { it.copy(isScanning = true, savedMessage = null) }
        viewModelScope.launch {
            runCatching { movieRepository.scanLibrary(uri) }
                .onSuccess { count ->
                    _uiState.value = loadState().copy(savedMessage = "影片库扫描完成：$count 部影片")
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "影片库扫描失败")
                }
        }
    }

    fun reorganizeExistingLibraries() {
        _uiState.update { it.copy(isReorganizing = true, savedMessage = null) }
        viewModelScope.launch {
            val libraryRootUri = repository.getLibraryRootUri()
            if (libraryRootUri.isNullOrBlank()) {
                _uiState.value = loadState().copy(savedMessage = "请先选择影片库目录")
                return@launch
            }
            runCatching { movieRepository.reorganizeLibraryByActorFolders(Uri.parse(libraryRootUri)) }
                .onSuccess { result ->
                    val message = if (result.hasFailures) {
                        "整理完成：移动 ${result.movedFolders} 个文件夹，重新扫描 ${result.movieCount} 部影片，${result.failedRoots.size} 个目录失败"
                    } else {
                        "整理完成：移动 ${result.movedFolders} 个文件夹，重新扫描 ${result.movieCount} 部影片"
                    }
                    _uiState.value = loadState().copy(savedMessage = message)
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "影片库整理失败")
                }
        }
    }

    fun clearScrapeLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                scrapeRepository.clearLogs()
            }
            _uiState.update { it.copy(scrapeLog = "") }
        }
    }

    fun rebuildCloudStrmIndex() {
        _uiState.update { it.copy(isRebuildingStrmIndex = true, savedMessage = null) }
        viewModelScope.launch {
            runCatching { cloudStrmRecordRepository.rebuildIndexAndNormalizeSegments() }
                .onSuccess { result ->
                    _uiState.value = loadState().copy(
                        savedMessage = "STRM 索引已重建：${result.indexed} 个，规范化分段 ${result.renamed} 个"
                    )
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "STRM 索引重建失败")
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(savedMessage = null) }
    }

    private fun loadState(): SettingsUiState =
        SettingsUiState(
            cookies = repository.getCookies(),
            missavCookies = repository.getMissavCookies(),
            strmTreeUri = repository.getStrmTreeUri(),
            strmTreeDisplayName = repository.getStrmTreeDisplayName(),
            libraryRootUri = repository.getLibraryRootUri(),
            libraryRootDisplayName = repository.getLibraryRootDisplayName(),
            strmBaseUrl = repository.getStrmBaseUrl(),
            defaultScrapeSource = repository.getDefaultScrapeSource(),
            imageDownloadRetryCountText = repository.getImageDownloadRetryCount().toString(),
            scrapeConcurrencyLimitText = repository.getScrapeConcurrencyLimit().toString(),
            translateProvider = repository.getTranslateProvider(),
            baiduTranslateAppId = repository.getBaiduTranslateAppId(),
            baiduTranslateSecretKey = repository.getBaiduTranslateSecretKey(),
            deepSeekApiKey = repository.getDeepSeekApiKey(),
            deepSeekBaseUrl = repository.getDeepSeekBaseUrl(),
            deepSeekModel = repository.getDeepSeekModel(),
            deepSeekThinkingEnabled = repository.isDeepSeekThinkingEnabled(),
            deepSeekPromptEnabled = repository.isDeepSeekPromptEnabled(),
            deepSeekPromptOptions = DeepSeekPromptTemplates.options,
            deepSeekPromptTemplateId = repository.getDeepSeekPromptTemplateId(),
            deepSeekCustomPrompt = repository.getDeepSeekCustomPrompt(),
            domesticRootCidText = repository.getDomesticRootCidText(),
            libraryNoMediaEnabled = repository.isLibraryNoMediaEnabled(),
            cloudAddButtonMessageEnabled = repository.isCloudAddButtonMessageEnabled(),
            cloudExcludedVideoNames = repository.getCloudExcludedVideoNames().toList().sorted(),
            asrModelOptions = asrModelManager.availableModels(),
            selectedAsrModelId = repository.getAsrModelId(),
            asrModelBaseUrl = repository.getAsrModelBaseUrl(),
            isAsrModelReady = asrModelManager.currentStatus().isReady,
            asrModelSizeText = formatBytes(asrModelManager.currentStatus().sizeBytes),
            selectedCloud115LoginApp = Cloud115LoginApps.find(repository.getCloud115LoginApp()),
            savedCloud115Accounts = emptyList(),
            scrapeLog = ""
        )

    companion object {
        private const val QR_LOGIN_POLL_INTERVAL_MS = 1_500L

        fun factory(
            repository: AppSettingsRepository,
            movieRepository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            cloud115QrLoginClient: Cloud115QrLoginClient,
            asrModelManager: AsrModelManager
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repository,
                        movieRepository,
                        cloudStrmRecordRepository,
                        scrapeRepository,
                        cloud115QrLoginClient,
                        asrModelManager
                    ) as T
            }

        private fun formatBytes(bytes: Long): String {
            val mb = bytes / 1024.0 / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
        }
    }
}

data class SettingsUiState(
    val cookies: String = "",
    val missavCookies: String = "",
    val strmTreeUri: String? = null,
    val strmTreeDisplayName: String = "尚未选择目录",
    val libraryRootUri: String? = null,
    val libraryRootDisplayName: String = "尚未选择目录",
    val strmBaseUrl: String = AppSettingsRepository.DEFAULT_STRM_BASE_URL,
    val defaultScrapeSource: ScrapeSource = ScrapeSource.Dmm2,
    val imageDownloadRetryCountText: String = AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT.toString(),
    val scrapeConcurrencyLimitText: String = AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT.toString(),
    val translateProvider: TranslateProvider = TranslateProvider.Baidu,
    val baiduTranslateAppId: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_APP_ID,
    val baiduTranslateSecretKey: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_SECRET_KEY,
    val deepSeekApiKey: String = "",
    val deepSeekBaseUrl: String = AppSettingsRepository.DEFAULT_DEEPSEEK_BASE_URL,
    val deepSeekModel: String = AppSettingsRepository.DEFAULT_DEEPSEEK_MODEL,
    val deepSeekThinkingEnabled: Boolean = false,
    val deepSeekPromptEnabled: Boolean = true,
    val deepSeekPromptOptions: List<DeepSeekPromptTemplate> = DeepSeekPromptTemplates.options,
    val deepSeekPromptTemplateId: String = DeepSeekPromptTemplates.DEFAULT_ID,
    val deepSeekCustomPrompt: String = "",
    val domesticRootCidText: String = "",
    val libraryNoMediaEnabled: Boolean = true,
    val cloudAddButtonMessageEnabled: Boolean = true,
    val cloudExcludedVideoNames: List<String> = emptyList(),
    val newExcludedVideoName: String = "",
    val asrModelOptions: List<AsrModelOption> = emptyList(),
    val selectedAsrModelId: String = AppSettingsRepository.DEFAULT_ASR_MODEL_ID,
    val asrModelBaseUrl: String = AppSettingsRepository.DEFAULT_ASR_MODEL_BASE_URL,
    val isAsrModelReady: Boolean = false,
    val asrModelSizeText: String = "0 KB",
    val isAsrModelDownloading: Boolean = false,
    val asrModelDownloadProgress: Int = 0,
    val asrModelDownloadMessage: String = "",
    val selectedCloud115LoginApp: Cloud115LoginApp = Cloud115LoginApps.default,
    val savedCloud115Accounts: List<SavedCloud115Account> = emptyList(),
    val selectedCloud115AccountFileName: String? = null,
    val cloud115QrToken: Cloud115QrToken? = null,
    val isCloud115QrLoginActive: Boolean = false,
    val cloud115QrStatusText: String = "",
    val cloud115QrSavedFile: String? = null,
    val isScanning: Boolean = false,
    val isReorganizing: Boolean = false,
    val isRebuildingStrmIndex: Boolean = false,
    val isScraping: Boolean = false,
    val scrapeLog: String = "",
    val savedMessage: String? = null
) {
    val hasMissavCookie: Boolean
        get() = missavCookies.isNotBlank()
}

