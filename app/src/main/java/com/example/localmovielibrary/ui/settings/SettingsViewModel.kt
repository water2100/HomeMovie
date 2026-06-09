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
import com.example.localmovielibrary.data.repository.AppUpdateInfo
import com.example.localmovielibrary.data.repository.AppUpdateRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.ScrapeTaskSummary
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.MissavScrapeLanguage
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
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
    private val appUpdateRepository: AppUpdateRepository,
    private val cloud115QrLoginClient: Cloud115QrLoginClient,
    private val asrModelManager: AsrModelManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    private var qrLoginJob: Job? = null
    private var asrDownloadJob: Job? = null
    private var manualScrapeJob: Job? = null
    private var appUpdateJob: Job? = null
    private var mgstageRuleJob: Job? = null

    init {
        refreshScrapeTaskSummary()
    }

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

    fun updateMissavScrapeLanguage(language: MissavScrapeLanguage) {
        repository.saveMissavScrapeLanguage(language)
        _uiState.update {
            it.copy(
                missavScrapeLanguage = language,
                savedMessage = null
            )
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(strmBaseUrl = value, savedMessage = null) }
    }

    fun updateManifestUrl(value: String) {
        _uiState.update {
            it.copy(
                updateManifestUrl = value.take(500),
                updateMessage = "",
                savedMessage = null
            )
        }
    }

    fun updateProxyBaseUrl(value: String) {
        _uiState.update {
            it.copy(
                updateProxyBaseUrl = value.take(300),
                updateMessage = "",
                savedMessage = null
            )
        }
    }

    fun updateAutoCheckUpdateOnStartupEnabled(enabled: Boolean) {
        repository.saveUpdateAutoCheckOnStartupEnabled(enabled)
        _uiState.update {
            it.copy(
                autoCheckUpdateOnStartupEnabled = enabled,
                savedMessage = if (enabled) "已开启启动后自动检查更新" else "已关闭启动后自动检查更新"
            )
        }
    }

    fun updateAutoDeleteInstalledUpdateApkEnabled(enabled: Boolean) {
        repository.saveUpdateAutoDeleteInstalledApkEnabled(enabled)
        _uiState.update {
            it.copy(
                autoDeleteInstalledUpdateApkEnabled = enabled,
                savedMessage = if (enabled) "已开启安装后自动删除更新 APK" else "已关闭安装后自动删除更新 APK"
            )
        }
    }

    fun updateRemoteScrapeConfigUrl(value: String) {
        _uiState.update {
            it.copy(
                remoteScrapeConfigUrl = value.take(500),
                savedMessage = null
            )
        }
    }

    fun checkForAppUpdate() {
        if (appUpdateJob?.isActive == true) return
        val url = _uiState.value.updateManifestUrl.trim()
        repository.saveUpdateManifestUrl(url)
        repository.saveUpdateProxyBaseUrl(_uiState.value.updateProxyBaseUrl)
        if (url.isBlank()) {
            _uiState.update { it.copy(updateMessage = "请先填写版本信息地址") }
            return
        }
        appUpdateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingUpdate = true,
                    latestAppUpdate = null,
                    hasAppUpdate = false,
                    updateMessage = "正在检查更新...",
                    savedMessage = null
                )
            }
            runCatching { appUpdateRepository.checkForUpdate(url) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestAppUpdate = result.latest,
                            hasAppUpdate = result.hasUpdate,
                            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = if (result.hasUpdate) {
                                "发现新版本 ${result.latest.versionName}"
                            } else {
                                "当前已是最新版本"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateMessage = error.message ?: "检查更新失败"
                        )
                    }
                }
        }
    }

    fun downloadAndInstallUpdate() {
        if (appUpdateJob?.isActive == true) return
        val update = _uiState.value.latestAppUpdate
        if (update == null || !_uiState.value.hasAppUpdate) {
            _uiState.update { it.copy(updateMessage = "请先检查更新") }
            return
        }
        appUpdateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingUpdate = true,
                    updateDownloadProgress = 0,
                    updateMessage = "正在下载更新...",
                    savedMessage = null
                )
            }
            runCatching {
                appUpdateRepository.downloadAndInstall(update) { progress ->
                    _uiState.update {
                        it.copy(
                            updateDownloadProgress = progress,
                            updateMessage = "正在下载更新 $progress%"
                        )
                    }
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateDownloadProgress = 100,
                        downloadedUpdateReady = true,
                        downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                        updateMessage = "APK 已下载，已打开安装器"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                        downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                        updateMessage = error.message ?: "下载更新失败"
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        if (appUpdateJob?.isActive == true) return
        appUpdateJob = viewModelScope.launch {
            runCatching { appUpdateRepository.installDownloadedApk() }
                .onSuccess { installed ->
                    _uiState.update {
                        it.copy(
                            downloadedUpdateReady = installed || appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = if (installed) "已打开安装器" else "没有已下载的 APK"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = error.message ?: "安装 APK 失败"
                        )
                    }
                }
        }
    }

    fun addPriorityScrapeSource(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources
        if (source !in _uiState.value.priorityScrapeSourceOptions || source in current) return
        savePriorityScrapeSources(current + source)
    }

    fun removePriorityScrapeSource(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources
        if (current.size <= 1) {
            _uiState.update { it.copy(savedMessage = "至少保留一个优先级刮削来源") }
            return
        }
        savePriorityScrapeSources(current - source)
    }

    fun movePriorityScrapeSourceUp(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources.toMutableList()
        val index = current.indexOf(source)
        if (index <= 0) return
        current[index] = current[index - 1]
        current[index - 1] = source
        savePriorityScrapeSources(current)
    }

    fun movePriorityScrapeSourceDown(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources.toMutableList()
        val index = current.indexOf(source)
        if (index < 0 || index >= current.lastIndex) return
        current[index] = current[index + 1]
        current[index + 1] = source
        savePriorityScrapeSources(current)
    }

    private fun savePriorityScrapeSources(sources: List<ScrapeSource>) {
        repository.savePriorityScrapeSources(sources)
        _uiState.update {
            it.copy(
                priorityScrapeSources = repository.getPriorityScrapeSources(),
                savedMessage = null
            )
        }
    }

    fun updateImageDownloadRetryCount(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(imageDownloadRetryCountText = cleaned, savedMessage = null) }
    }

    fun updateScrapeConcurrencyLimit(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(1)
        _uiState.update { it.copy(scrapeConcurrencyLimitText = cleaned, savedMessage = null) }
    }

    fun updateNewDmm2SkippedPrefix(value: String) {
        _uiState.update {
            it.copy(
                newDmm2SkippedPrefix = value.uppercase().filter { char -> char.isLetterOrDigit() }.take(12),
                savedMessage = null
            )
        }
    }

    fun addDmm2SkippedPrefix() {
        val prefix = _uiState.value.newDmm2SkippedPrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要跳过的番号开头") }
            return
        }
        repository.addDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已添加 DMM2 跳过前缀：${prefix.uppercase()}")
    }

    fun removeDmm2SkippedPrefix(prefix: String) {
        repository.removeDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已移除 DMM2 跳过前缀")
    }

    fun updateNewMgstagePrefix(value: String) {
        _uiState.update {
            it.copy(
                newMgstagePrefix = value.uppercase().filter { char -> char.isLetterOrDigit() }.take(16),
                savedMessage = null
            )
        }
    }

    fun addCustomMgstagePrefix() {
        val prefix = _uiState.value.newMgstagePrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入 MGStage 番号开头") }
            return
        }
        if (prefix.none { it.isLetter() }) {
            _uiState.update { it.copy(savedMessage = "MGStage 番号前缀需要包含字母") }
            return
        }
        repository.saveRemoteScrapeConfigUrl(_uiState.value.remoteScrapeConfigUrl)
        repository.addCustomMgstageNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已添加 MGStage 前缀：${prefix.uppercase()}")
    }

    fun removeCustomMgstagePrefix(prefix: String) {
        repository.saveRemoteScrapeConfigUrl(_uiState.value.remoteScrapeConfigUrl)
        repository.removeCustomMgstageNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已移除 MGStage 自定义前缀")
    }

    fun refreshMgstageRules() {
        if (mgstageRuleJob?.isActive == true) return
        repository.saveRemoteScrapeConfigUrl(_uiState.value.remoteScrapeConfigUrl)
        mgstageRuleJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshingMgstageRules = true,
                    savedMessage = null
                )
            }
            runCatching { scrapeRepository.refreshMgstageNumberPrefixes() }
                .onSuccess { prefixes ->
                    _uiState.value = loadState().copy(
                        isRefreshingMgstageRules = false,
                        savedMessage = "MGStage GitHub 规则已刷新，合并 ${prefixes.size} 个前缀"
                    )
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshingMgstageRules = false,
                            savedMessage = error.message ?: "MGStage GitHub 规则刷新失败"
                        )
                    }
                }
        }
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

    fun updateDomesticPageEnabled(enabled: Boolean) {
        repository.saveDomesticPageEnabled(enabled)
        _uiState.update { it.copy(domesticPageEnabled = enabled, savedMessage = null) }
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

    fun updateCloudScrapeSkipBelowSizeMb(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(cloudScrapeSkipBelowSizeMbText = cleaned, savedMessage = null) }
    }

    fun updateAsrModel(model: AsrModelOption) {
        repository.saveAsrModelId(model.id)
        _uiState.value = loadState().copy(savedMessage = "ASR 模型已选择：${model.label}")
    }

    fun updateAsrModelBaseUrl(value: String) {
        _uiState.update { it.copy(asrModelBaseUrl = value.trim(), savedMessage = null) }
    }

    fun updatePlayerLiveSubtitleEnabled(enabled: Boolean) {
        repository.savePlayerLiveSubtitleEnabled(enabled)
        _uiState.update {
            it.copy(
                playerLiveSubtitleEnabled = enabled,
                savedMessage = if (enabled) "已开启播放器实时字幕" else "已关闭播放器实时字幕"
            )
        }
    }

    fun updateExternalSubtitleFontSizeSp(value: Int) {
        repository.saveExternalSubtitleFontSizeSp(value)
        _uiState.update {
            it.copy(
                externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
                savedMessage = "外挂字幕字号已调整"
            )
        }
    }

    fun updateExternalSubtitleBottomPaddingPercent(value: Int) {
        repository.saveExternalSubtitleBottomPaddingPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
                savedMessage = "外挂字幕位置已调整"
            )
        }
    }

    fun updateExternalSubtitleBackgroundAlphaPercent(value: Int) {
        repository.saveExternalSubtitleBackgroundAlphaPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
                savedMessage = "外挂字幕背景已调整"
            )
        }
    }

    fun updateSubtitleSearchProvider(provider: SubtitleSearchProvider) {
        repository.saveSubtitleSearchProvider(provider)
        _uiState.update {
            it.copy(
                subtitleSearchProvider = provider,
                savedMessage = "在线字幕来源已切换为：${provider.label}"
            )
        }
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
        repository.saveDomesticPageEnabled(state.domesticPageEnabled)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveCloudScrapeSkipBelowSizeMb(state.cloudScrapeSkipBelowSizeMbText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        repository.savePlayerLiveSubtitleEnabled(state.playerLiveSubtitleEnabled)
        repository.saveSubtitleSearchProvider(state.subtitleSearchProvider)
        _uiState.value = loadState().copy(savedMessage = "翻译配置已保存")
    }

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveMissavCookies(state.missavCookies)
        repository.saveMissavScrapeLanguage(state.missavScrapeLanguage)
        repository.saveUpdateManifestUrl(state.updateManifestUrl)
        repository.saveUpdateProxyBaseUrl(state.updateProxyBaseUrl)
        repository.saveUpdateAutoCheckOnStartupEnabled(state.autoCheckUpdateOnStartupEnabled)
        repository.saveUpdateAutoDeleteInstalledApkEnabled(state.autoDeleteInstalledUpdateApkEnabled)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveScrapeConcurrencyLimit(state.scrapeConcurrencyLimitText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
        repository.saveDmm2SkippedNumberPrefixes(state.dmm2SkippedPrefixes.toSet())
        repository.saveRemoteScrapeConfigUrl(state.remoteScrapeConfigUrl)
        repository.saveCustomMgstageNumberPrefixes(state.mgstageCustomPrefixes.toSet())
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
        repository.saveDomesticPageEnabled(state.domesticPageEnabled)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveCloudScrapeSkipBelowSizeMb(state.cloudScrapeSkipBelowSizeMbText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        repository.savePlayerLiveSubtitleEnabled(state.playerLiveSubtitleEnabled)
        repository.saveSubtitleSearchProvider(state.subtitleSearchProvider)
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

    fun refreshScrapeTaskSummary() {
        viewModelScope.launch {
            val summary = movieRepository.scrapeTaskSummary()
            _uiState.update { it.copy(scrapeTaskSummary = summary) }
        }
    }

    fun startManualScrapeTasks() {
        if (manualScrapeJob?.isActive == true) return
        manualScrapeJob = viewModelScope.launch {
            val source = repository.getDefaultScrapeSource()
            _uiState.update {
                it.copy(
                    isManualScrapeRunning = true,
                    isScraping = true,
                    scrapeTaskMessage = "正在准备刮削任务...",
                    savedMessage = null
                )
            }
            var success = 0
            var failed = 0
            runCatching {
                val tasks = movieRepository.getManualScrapeTaskMovies()
                if (tasks.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isManualScrapeRunning = false,
                            isScraping = false,
                            scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                            scrapeTaskMessage = "没有待刮削任务",
                            savedMessage = "没有待刮削任务"
                        )
                    }
                    return@launch
                }
                scrapeRepository.appendLog("手动刮削任务开始：共 ${tasks.size} 个，来源：${source.name}")
                tasks.forEachIndexed { index, movie ->
                    val label = movie.videoName.ifBlank { movie.title }
                    _uiState.update {
                        it.copy(
                            scrapeTaskMessage = "正在刮削 ${index + 1}/${tasks.size}：$label",
                            scrapeTaskSummary = movieRepository.scrapeTaskSummary()
                        )
                    }
                    movieRepository.markScrapeTaskRunning(movie.id)
                    runCatching {
                        val result = scrapeRepository.scrapeMovieWithOutput(movie, source)
                        val refreshed = movieRepository.refreshMovieAfterScrape(movie, result.strmUri)
                        movieRepository.markScrapeTaskCompleted(refreshed?.id ?: movie.id)
                        success += 1
                        scrapeRepository.appendLog("手动刮削任务完成：$label")
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        failed += 1
                        val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
                        movieRepository.markScrapeTaskFailed(movie.id, reason)
                        scrapeRepository.appendLog("手动刮削任务失败：$label，原因：$reason")
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = error.message ?: "刮削任务启动失败"
                scrapeRepository.appendLog("手动刮削任务中断：$message")
                _uiState.update {
                    it.copy(
                        isManualScrapeRunning = false,
                        isScraping = false,
                        scrapeTaskMessage = message,
                        savedMessage = message
                    )
                }
                return@launch
            }
            val summary = movieRepository.scrapeTaskSummary()
            val message = "刮削任务完成：成功 $success 个，失败 $failed 个"
            scrapeRepository.appendLog(message)
            _uiState.update {
                it.copy(
                    isManualScrapeRunning = false,
                    isScraping = false,
                    scrapeTaskSummary = summary,
                    scrapeTaskMessage = message,
                    savedMessage = message
                )
            }
        }
    }

    fun resetFailedScrapeTasks() {
        viewModelScope.launch {
            val count = movieRepository.resetFailedScrapeTasks()
            _uiState.update {
                it.copy(
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    savedMessage = "已将 $count 个失败任务改为待刮削"
                )
            }
        }
    }

    fun clearFinishedScrapeTasks() {
        viewModelScope.launch {
            val count = movieRepository.clearFinishedScrapeTasks()
            _uiState.update {
                it.copy(
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    savedMessage = "已清理 $count 个已完成任务记录"
                )
            }
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

    private fun loadState(
        scrapeTaskSummary: ScrapeTaskSummary = ScrapeTaskSummary(),
        scrapeTaskMessage: String = ""
    ): SettingsUiState {
        val lastUpdateResult = appUpdateRepository.lastCheckResult
        return SettingsUiState(
            cookies = repository.getCookies(),
            missavCookies = repository.getMissavCookies(),
            missavScrapeLanguage = repository.getMissavScrapeLanguage(),
            missavScrapeLanguageOptions = MissavScrapeLanguage.entries,
            updateManifestUrl = repository.getUpdateManifestUrl(),
            updateProxyBaseUrl = repository.getUpdateProxyBaseUrl(),
            appVersionName = appUpdateRepository.currentVersionName,
            appVersionCode = appUpdateRepository.currentVersionCode,
            latestAppUpdate = lastUpdateResult?.latest,
            hasAppUpdate = lastUpdateResult?.hasUpdate ?: false,
            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
            updateApkDirectoryPath = appUpdateRepository.updateDirectoryPath,
            autoCheckUpdateOnStartupEnabled = repository.isUpdateAutoCheckOnStartupEnabled(),
            autoDeleteInstalledUpdateApkEnabled = repository.isUpdateAutoDeleteInstalledApkEnabled(),
            strmTreeUri = repository.getStrmTreeUri(),
            strmTreeDisplayName = repository.getStrmTreeDisplayName(),
            libraryRootUri = repository.getLibraryRootUri(),
            libraryRootDisplayName = repository.getLibraryRootDisplayName(),
            strmBaseUrl = repository.getStrmBaseUrl(),
            defaultScrapeSource = repository.getDefaultScrapeSource(),
            priorityScrapeSources = repository.getPriorityScrapeSources(),
            priorityScrapeSourceOptions = AppSettingsRepository.PRIORITY_SCRAPE_SOURCE_OPTIONS,
            imageDownloadRetryCountText = repository.getImageDownloadRetryCount().toString(),
            scrapeConcurrencyLimitText = repository.getScrapeConcurrencyLimit().toString(),
            dmm2SkippedPrefixes = repository.getDmm2SkippedNumberPrefixes().toList().sorted(),
            remoteScrapeConfigUrl = repository.getRemoteScrapeConfigUrl(),
            mgstageCustomPrefixes = repository.getCustomMgstageNumberPrefixes().toList().sorted(),
            mgstageRemotePrefixes = repository.getCachedMgstageNumberPrefixes().toList().sorted(),
            mgstageMergedPrefixes = repository.getMergedMgstageNumberPrefixes().toList().sorted(),
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
            domesticPageEnabled = repository.isDomesticPageEnabled(),
            libraryNoMediaEnabled = repository.isLibraryNoMediaEnabled(),
            cloudAddButtonMessageEnabled = repository.isCloudAddButtonMessageEnabled(),
            cloudExcludedVideoNames = repository.getCloudExcludedVideoNames().toList().sorted(),
            cloudScrapeSkipBelowSizeMbText = repository.getCloudScrapeSkipBelowSizeMb().toString(),
            asrModelOptions = asrModelManager.availableModels(),
            selectedAsrModelId = repository.getAsrModelId(),
            asrModelBaseUrl = repository.getAsrModelBaseUrl(),
            isAsrModelReady = asrModelManager.currentStatus().isReady,
            asrModelSizeText = formatBytes(asrModelManager.currentStatus().sizeBytes),
            playerLiveSubtitleEnabled = repository.isPlayerLiveSubtitleEnabled(),
            externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
            externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
            externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
            subtitleSearchProvider = repository.getSubtitleSearchProvider(),
            subtitleSearchProviderOptions = SubtitleSearchProvider.entries,
            selectedCloud115LoginApp = Cloud115LoginApps.find(repository.getCloud115LoginApp()),
            savedCloud115Accounts = emptyList(),
            scrapeTaskSummary = scrapeTaskSummary,
            scrapeTaskMessage = scrapeTaskMessage,
            scrapeLog = ""
        )
    }

    companion object {
        private const val QR_LOGIN_POLL_INTERVAL_MS = 1_500L

        fun factory(
            repository: AppSettingsRepository,
            movieRepository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            appUpdateRepository: AppUpdateRepository,
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
                        appUpdateRepository,
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
    val missavScrapeLanguage: MissavScrapeLanguage = MissavScrapeLanguage.Default,
    val missavScrapeLanguageOptions: List<MissavScrapeLanguage> = MissavScrapeLanguage.entries,
    val updateManifestUrl: String = "",
    val updateProxyBaseUrl: String = AppSettingsRepository.DEFAULT_UPDATE_PROXY_BASE_URL,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val latestAppUpdate: AppUpdateInfo? = null,
    val hasAppUpdate: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Int = 0,
    val downloadedUpdateReady: Boolean = false,
    val downloadedUpdateApkPath: String = "",
    val updateApkDirectoryPath: String = "",
    val autoCheckUpdateOnStartupEnabled: Boolean = true,
    val autoDeleteInstalledUpdateApkEnabled: Boolean = true,
    val updateMessage: String = "",
    val strmTreeUri: String? = null,
    val strmTreeDisplayName: String = "尚未选择目录",
    val libraryRootUri: String? = null,
    val libraryRootDisplayName: String = "尚未选择目录",
    val strmBaseUrl: String = AppSettingsRepository.DEFAULT_STRM_BASE_URL,
    val defaultScrapeSource: ScrapeSource = ScrapeSource.Priority,
    val priorityScrapeSources: List<ScrapeSource> = AppSettingsRepository.DEFAULT_PRIORITY_SCRAPE_SOURCES,
    val priorityScrapeSourceOptions: List<ScrapeSource> = AppSettingsRepository.PRIORITY_SCRAPE_SOURCE_OPTIONS,
    val imageDownloadRetryCountText: String = AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT.toString(),
    val scrapeConcurrencyLimitText: String = AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT.toString(),
    val dmm2SkippedPrefixes: List<String> = emptyList(),
    val newDmm2SkippedPrefix: String = "",
    val remoteScrapeConfigUrl: String = AppSettingsRepository.DEFAULT_REMOTE_SCRAPE_CONFIG_URL,
    val mgstageCustomPrefixes: List<String> = emptyList(),
    val mgstageRemotePrefixes: List<String> = AppSettingsRepository.DEFAULT_MGSTAGE_NUMBER_PREFIXES.toList().sorted(),
    val mgstageMergedPrefixes: List<String> = AppSettingsRepository.DEFAULT_MGSTAGE_NUMBER_PREFIXES.toList().sorted(),
    val newMgstagePrefix: String = "",
    val isRefreshingMgstageRules: Boolean = false,
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
    val domesticPageEnabled: Boolean = false,
    val libraryNoMediaEnabled: Boolean = true,
    val cloudAddButtonMessageEnabled: Boolean = true,
    val cloudExcludedVideoNames: List<String> = emptyList(),
    val newExcludedVideoName: String = "",
    val cloudScrapeSkipBelowSizeMbText: String = AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB.toString(),
    val asrModelOptions: List<AsrModelOption> = emptyList(),
    val selectedAsrModelId: String = AppSettingsRepository.DEFAULT_ASR_MODEL_ID,
    val asrModelBaseUrl: String = AppSettingsRepository.DEFAULT_ASR_MODEL_BASE_URL,
    val isAsrModelReady: Boolean = false,
    val asrModelSizeText: String = "0 KB",
    val playerLiveSubtitleEnabled: Boolean = false,
    val externalSubtitleFontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val externalSubtitleBottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val externalSubtitleBackgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
    val subtitleSearchProvider: SubtitleSearchProvider = SubtitleSearchProvider.Xunlei,
    val subtitleSearchProviderOptions: List<SubtitleSearchProvider> = SubtitleSearchProvider.entries,
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
    val isManualScrapeRunning: Boolean = false,
    val scrapeTaskSummary: ScrapeTaskSummary = ScrapeTaskSummary(),
    val scrapeTaskMessage: String = "",
    val scrapeLog: String = "",
    val savedMessage: String? = null
) {
    val hasMissavCookie: Boolean
        get() = missavCookies.isNotBlank()
}

