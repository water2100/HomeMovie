package com.example.localmovielibrary.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.ScrapeSource
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
    private val scrapeRepository: StrmScrapeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    fun updateCookies(value: String) {
        _uiState.update { it.copy(cookies = value, savedMessage = null) }
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

    fun updateBaiduTranslateAppId(value: String) {
        _uiState.update { it.copy(baiduTranslateAppId = value.trim(), savedMessage = null) }
    }

    fun updateBaiduTranslateSecretKey(value: String) {
        _uiState.update { it.copy(baiduTranslateSecretKey = value.trim(), savedMessage = null) }
    }

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveMissavCookies(state.missavCookies)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveBaiduTranslateAppId(state.baiduTranslateAppId)
        repository.saveBaiduTranslateSecretKey(state.baiduTranslateSecretKey)
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
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "STRM 绱㈠紩閲嶅缓澶辫触")
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
            baiduTranslateAppId = repository.getBaiduTranslateAppId(),
            baiduTranslateSecretKey = repository.getBaiduTranslateSecretKey(),
            scrapeLog = ""
        )

    companion object {
        fun factory(
            repository: AppSettingsRepository,
            movieRepository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(repository, movieRepository, cloudStrmRecordRepository, scrapeRepository) as T
            }
    }
}

data class SettingsUiState(
    val cookies: String = "",
    val missavCookies: String = "",
    val strmTreeUri: String? = null,
    val strmTreeDisplayName: String = "灏氭湭閫夋嫨鐩綍",
    val libraryRootUri: String? = null,
    val libraryRootDisplayName: String = "灏氭湭閫夋嫨鐩綍",
    val strmBaseUrl: String = AppSettingsRepository.DEFAULT_STRM_BASE_URL,
    val defaultScrapeSource: ScrapeSource = ScrapeSource.Official,
    val imageDownloadRetryCountText: String = AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT.toString(),
    val baiduTranslateAppId: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_APP_ID,
    val baiduTranslateSecretKey: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_SECRET_KEY,
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

