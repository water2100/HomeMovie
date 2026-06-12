package com.example.localmovielibrary.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrary.cloud115.Cloud115QrLoginStatus
import com.example.localmovielibrary.cloud115.Cloud115QrToken
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.AppUpdateInfo
import com.example.localmovielibrary.data.repository.AppUpdateRepository
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRepository
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRunner
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.ScrapeTaskSummary
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.MissavScrapeLanguage
import com.example.localmovielibrary.util.NumberRecognitionRules
import kotlinx.coroutines.CancellationException
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
    private val cloudFolderBatchTaskRepository: CloudFolderBatchTaskRepository,
    private val cloudFolderBatchTaskRunner: CloudFolderBatchTaskRunner
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    private var qrLoginJob: Job? = null
    private var manualScrapeJob: Job? = null
    private var appUpdateJob: Job? = null
    private var mgstageRuleJob: Job? = null

    init {
        refreshScrapeTaskSummary()
        observeCloudFolderBatchTasks()
        observeCloudFolderBatchRunner()
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

    fun updateUseUpdateProxyEnabled(enabled: Boolean) {
        repository.saveUpdateProxyEnabled(enabled)
        _uiState.update {
            it.copy(
                useUpdateProxyEnabled = enabled,
                updateMessage = "",
                savedMessage = if (enabled) "已开启代理更新" else "已关闭代理更新"
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
        repository.saveUpdateProxyEnabled(_uiState.value.useUpdateProxyEnabled)
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

    fun updateNewMgstageNumericPrefix(value: String) {
        _uiState.update {
            it.copy(
                newMgstageNumericPrefix = value.filter(Char::isDigit).take(4),
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
        if (prefix.firstOrNull()?.isDigit() == true) {
            _uiState.update { it.copy(savedMessage = "请填写不带附加数字的标准前缀，例如 MIUM 或 SCUTE") }
            return
        }
        repository.saveRemoteScrapeConfigUrl(_uiState.value.remoteScrapeConfigUrl)
        val numericPrefix = _uiState.value.newMgstageNumericPrefix
        repository.addCustomMgstageNumberPrefix(prefix, numericPrefix)
        _uiState.value = loadState().copy(
            savedMessage = "已添加 MGStage 规则：${prefix.uppercase()} → ${numericPrefix.ifBlank { "无需附加" }}"
        )
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
                        savedMessage = "GitHub 刮削规则已刷新，合并 ${prefixes.size} 个 MGStage 前缀"
                    )
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshingMgstageRules = false,
                            savedMessage = error.message ?: "GitHub 刮削规则刷新失败"
                        )
                    }
                }
        }
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

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveMissavCookies(state.missavCookies)
        repository.saveMissavScrapeLanguage(state.missavScrapeLanguage)
        repository.saveUpdateManifestUrl(state.updateManifestUrl)
        repository.saveUpdateProxyBaseUrl(state.updateProxyBaseUrl)
        repository.saveUpdateProxyEnabled(state.useUpdateProxyEnabled)
        repository.saveUpdateAutoCheckOnStartupEnabled(state.autoCheckUpdateOnStartupEnabled)
        repository.saveUpdateAutoDeleteInstalledApkEnabled(state.autoDeleteInstalledUpdateApkEnabled)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveScrapeConcurrencyLimit(state.scrapeConcurrencyLimitText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
        repository.saveDmm2SkippedNumberPrefixes(state.dmm2SkippedPrefixes.toSet())
        repository.saveRemoteScrapeConfigUrl(state.remoteScrapeConfigUrl)
        repository.saveCustomMgstagePrefixNumberMappings(state.mgstageCustomPrefixes)
        repository.saveDomesticRootCid(state.domesticRootCidText)
        repository.saveDomesticPageEnabled(state.domesticPageEnabled)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveCloudScrapeSkipBelowSizeMb(state.cloudScrapeSkipBelowSizeMbText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
        _uiState.value = loadState().copy(savedMessage = "设置已保存")
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

    fun refreshScrapeTaskSummary() {
        viewModelScope.launch {
            val summary = movieRepository.scrapeTaskSummary()
            _uiState.update { it.copy(scrapeTaskSummary = summary) }
        }
    }

    private fun observeCloudFolderBatchTasks() {
        viewModelScope.launch {
            cloudFolderBatchTaskRepository.observeTasks().collect { tasks ->
                _uiState.update { it.copy(cloudFolderBatchTasks = tasks) }
            }
        }
    }

    private fun observeCloudFolderBatchRunner() {
        viewModelScope.launch {
            cloudFolderBatchTaskRunner.isRunning.collect { running ->
                _uiState.update {
                    it.copy(
                        isCloudFolderBatchRunning = running,
                        isScraping = running || it.isManualScrapeRunning,
                        cloudFolderBatchTaskMessage = if (running) {
                            "正在执行网盘文件夹任务"
                        } else {
                            it.cloudFolderBatchTaskMessage
                        }
                    )
                }
            }
        }
    }

    fun startCloudFolderBatchTasks() {
        val unfinished = _uiState.value.cloudFolderBatchTasks.count { it.status != "Completed" }
        if (unfinished <= 0) {
            _uiState.update {
                it.copy(
                    cloudFolderBatchTaskMessage = "没有待执行的网盘文件夹任务",
                    savedMessage = "没有待执行的网盘文件夹任务"
                )
            }
            return
        }
        cloudFolderBatchTaskRunner.start()
        _uiState.update {
            it.copy(
                cloudFolderBatchTaskMessage = "正在启动网盘文件夹任务...",
                savedMessage = null
            )
        }
    }

    fun stopCloudFolderBatchTasks() {
        cloudFolderBatchTaskRunner.stop()
        _uiState.update {
            it.copy(
                cloudFolderBatchTaskMessage = "正在暂停网盘文件夹任务...",
                savedMessage = "已请求暂停网盘文件夹任务"
            )
        }
    }

    fun refreshCloudFolderBatchTasks() {
        _uiState.update {
            it.copy(
                cloudFolderBatchTaskMessage = "任务列表已刷新"
            )
        }
    }

    fun clearCompletedCloudFolderBatchTasks() {
        viewModelScope.launch {
            val count = cloudFolderBatchTaskRepository.clearCompletedTasks()
            _uiState.update {
                it.copy(
                    cloudFolderBatchTaskMessage = "已清理 $count 个已完成文件夹任务",
                    savedMessage = "已清理 $count 个已完成文件夹任务"
                )
            }
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

    fun stopManualScrapeTasks() {
        if (manualScrapeJob?.isActive != true) return
        manualScrapeJob?.cancel(CancellationException("已手动暂停刮削任务"))
        viewModelScope.launch {
            movieRepository.resetRunningScrapeTasks()
            _uiState.update {
                it.copy(
                    isManualScrapeRunning = false,
                    isScraping = it.isCloudFolderBatchRunning,
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    scrapeTaskMessage = "已暂停刮削任务，点击开始可继续处理剩余任务",
                    savedMessage = "已暂停刮削任务"
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
            useUpdateProxyEnabled = repository.isUpdateProxyEnabled(),
            appVersionName = appUpdateRepository.currentVersionName,
            appVersionCode = appUpdateRepository.currentVersionCode,
            latestAppUpdate = lastUpdateResult?.latest,
            hasAppUpdate = lastUpdateResult?.hasUpdate ?: false,
            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
            updateApkDirectoryPath = appUpdateRepository.updateDirectoryPath,
            autoCheckUpdateOnStartupEnabled = repository.isUpdateAutoCheckOnStartupEnabled(),
            autoDeleteInstalledUpdateApkEnabled = repository.isUpdateAutoDeleteInstalledApkEnabled(),
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
            mgstageCustomPrefixes = repository.getCustomMgstagePrefixNumberMappings().toSortedMap(),
            mgstageRemotePrefixes = repository.getCachedMgstagePrefixNumberMappings().toSortedMap(),
            mgstageMergedPrefixes = repository.getMergedMgstagePrefixNumberMappings().toSortedMap(),
            numberRecognitionIgnoredSuffixes = repository.getCachedNumberRecognitionIgnoredSuffixes().toList().sorted(),
            numberRecognitionPartMarkers = repository.getCachedNumberRecognitionPartMarkers().toList().sorted(),
            numberRecognitionAttachedLetterPrefixes = NumberRecognitionRules.DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES.toList().sorted(),
            numberRecognitionNumericPrefixAliases = repository.getMergedMgstageSearchPrefixAliases().toSortedMap(),
            domesticRootCidText = repository.getDomesticRootCidText(),
            domesticPageEnabled = repository.isDomesticPageEnabled(),
            libraryNoMediaEnabled = repository.isLibraryNoMediaEnabled(),
            cloudAddButtonMessageEnabled = repository.isCloudAddButtonMessageEnabled(),
            cloudExcludedVideoNames = repository.getCloudExcludedVideoNames().toList().sorted(),
            cloudScrapeSkipBelowSizeMbText = repository.getCloudScrapeSkipBelowSizeMb().toString(),
            externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
            externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
            externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
            selectedCloud115LoginApp = Cloud115LoginApps.find(repository.getCloud115LoginApp()),
            savedCloud115Accounts = emptyList(),
            scrapeTaskSummary = scrapeTaskSummary,
            scrapeTaskMessage = scrapeTaskMessage
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
            cloudFolderBatchTaskRepository: CloudFolderBatchTaskRepository,
            cloudFolderBatchTaskRunner: CloudFolderBatchTaskRunner
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
                        cloudFolderBatchTaskRepository,
                        cloudFolderBatchTaskRunner
                    ) as T
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
    val useUpdateProxyEnabled: Boolean = true,
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
    val mgstageCustomPrefixes: Map<String, String> = emptyMap(),
    val mgstageRemotePrefixes: Map<String, String> = AppSettingsRepository.DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES
        .mapValues { (prefix, searchPrefix) -> searchPrefix.removeSuffix(prefix).filter(Char::isDigit) }
        .plus("SIRO" to "")
        .toSortedMap(),
    val mgstageMergedPrefixes: Map<String, String> = mgstageRemotePrefixes,
    val newMgstagePrefix: String = "",
    val newMgstageNumericPrefix: String = "",
    val numberRecognitionIgnoredSuffixes: List<String> = AppSettingsRepository.DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES.toList().sorted(),
    val numberRecognitionPartMarkers: List<String> = AppSettingsRepository.DEFAULT_NUMBER_RECOGNITION_PART_MARKERS.toList().sorted(),
    val numberRecognitionAttachedLetterPrefixes: List<String> = NumberRecognitionRules.DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES.toList().sorted(),
    val numberRecognitionNumericPrefixAliases: Map<String, String> = AppSettingsRepository.DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES.toSortedMap(),
    val isRefreshingMgstageRules: Boolean = false,
    val domesticRootCidText: String = "",
    val domesticPageEnabled: Boolean = false,
    val libraryNoMediaEnabled: Boolean = true,
    val cloudAddButtonMessageEnabled: Boolean = true,
    val cloudExcludedVideoNames: List<String> = emptyList(),
    val newExcludedVideoName: String = "",
    val cloudScrapeSkipBelowSizeMbText: String = AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB.toString(),
    val externalSubtitleFontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val externalSubtitleBottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val externalSubtitleBackgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
    val selectedCloud115LoginApp: Cloud115LoginApp = Cloud115LoginApps.default,
    val savedCloud115Accounts: List<SavedCloud115Account> = emptyList(),
    val selectedCloud115AccountFileName: String? = null,
    val cloud115QrToken: Cloud115QrToken? = null,
    val isCloud115QrLoginActive: Boolean = false,
    val cloud115QrStatusText: String = "",
    val cloud115QrSavedFile: String? = null,
    val isScanning: Boolean = false,
    val isRebuildingStrmIndex: Boolean = false,
    val isScraping: Boolean = false,
    val isManualScrapeRunning: Boolean = false,
    val scrapeTaskSummary: ScrapeTaskSummary = ScrapeTaskSummary(),
    val scrapeTaskMessage: String = "",
    val cloudFolderBatchTasks: List<CloudFolderBatchTaskEntity> = emptyList(),
    val isCloudFolderBatchRunning: Boolean = false,
    val cloudFolderBatchTaskMessage: String = "",
    val savedMessage: String? = null
) {
    val hasMissavCookie: Boolean
        get() = missavCookies.isNotBlank()
}

