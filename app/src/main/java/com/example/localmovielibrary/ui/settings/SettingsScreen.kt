package com.example.localmovielibrary.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.translate.DeepSeekPromptTemplate
import com.example.localmovielibrary.translate.DeepSeekPromptTemplates
import com.example.localmovielibrary.translate.TranslateProvider
import com.example.localmovielibrary.ui.shared.MovieImageCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SettingsBackground = Color(0xFF070A0E)

private enum class SettingsPage {
    Directory,
    Cloud,
    Scrape,
    Subtitle,
    Translate,
    Player
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenScrapeLogs: () -> Unit,
    onOpenMissavWeb: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strmDirectoryPicker = rememberTreePicker { uri -> viewModel.saveStrmDirectory(uri) }
    val libraryDirectoryPicker = rememberTreePicker { uri -> viewModel.scanLibrary(uri) }
    var currentPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var showStrmBaseUrlDialog by remember { mutableStateOf(false) }
    var showImageCacheDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var imageCacheSizeText by remember { mutableStateOf("计算中...") }

    BackHandler(enabled = currentPage != null) {
        currentPage = null
    }

    fun refreshImageCacheSize() {
        scope.launch {
            val sizeBytes = withContext(Dispatchers.IO) {
                MovieImageCacheStore.diskCacheSizeBytes(context)
            }
            imageCacheSizeText = formatCacheSize(sizeBytes)
        }
    }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            val snackbarJob = launch { snackbarHostState.showSnackbar(it) }
            delay(1_600)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob.cancel()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) {
        refreshImageCacheSize()
    }

    LaunchedEffect(currentPage) {
        if (currentPage == SettingsPage.Cloud) {
            viewModel.refreshSavedCloud115Accounts()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = currentPage?.titleText() ?: "设置",
                onBack = currentPage?.let { { currentPage = null } }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (currentPage) {
                    null -> SettingsOverviewPage(
                        uiState = uiState,
                        imageCacheSizeText = imageCacheSizeText,
                        onOpenPage = { currentPage = it },
                        onOpenLogs = onOpenScrapeLogs,
                        onSave = viewModel::save
                    )

                    SettingsPage.Directory -> DirectorySettingsPage(
                        uiState = uiState,
                        onPickLibrary = { libraryDirectoryPicker.launch(null) },
                        onPickStrmDirectory = { strmDirectoryPicker.launch(null) },
                        onNoMediaEnabledChange = viewModel::updateLibraryNoMediaEnabled,
                        onReorganize = viewModel::reorganizeExistingLibraries,
                        onRebuildIndex = viewModel::rebuildCloudStrmIndex
                    )

                    SettingsPage.Cloud -> CloudSettingsPage(
                        uiState = uiState,
                        onCloud115AppSelected = viewModel::selectCloud115LoginApp,
                        onSavedCloud115AccountSelected = viewModel::applySavedCloud115Account,
                        onSavedCloud115AccountDelete = viewModel::deleteSavedCloud115Account,
                        onRefreshSavedCloud115Accounts = viewModel::refreshSavedCloud115Accounts,
                        onDomesticRootCidChange = viewModel::updateDomesticRootCid,
                        onDomesticPageEnabledChange = viewModel::updateDomesticPageEnabled,
                        onStartCloud115QrLogin = viewModel::startCloud115QrLogin,
                        onCancelCloud115QrLogin = viewModel::cancelCloud115QrLogin,
                        onOpenStrmBaseUrlDialog = { showStrmBaseUrlDialog = true },
                        onCloudAddButtonMessageEnabledChange = viewModel::updateCloudAddButtonMessageEnabled,
                        onExcludedVideoNameDraftChange = viewModel::updateNewExcludedVideoName,
                        onAddExcludedVideoName = viewModel::addExcludedVideoName,
                        onRemoveExcludedVideoName = viewModel::removeExcludedVideoName
                    )

                    SettingsPage.Scrape -> ScrapeSettingsPage(
                        uiState = uiState,
                        imageCacheSizeText = imageCacheSizeText,
                        onSourceSelected = viewModel::updateDefaultScrapeSource,
                        onRetryCountChange = viewModel::updateImageDownloadRetryCount,
                        onConcurrencyLimitChange = viewModel::updateScrapeConcurrencyLimit,
                        onDmm2SkippedPrefixDraftChange = viewModel::updateNewDmm2SkippedPrefix,
                        onAddDmm2SkippedPrefix = viewModel::addDmm2SkippedPrefix,
                        onRemoveDmm2SkippedPrefix = viewModel::removeDmm2SkippedPrefix,
                        onRefreshCacheSize = ::refreshImageCacheSize,
                        onClearImageCache = { showImageCacheDialog = true },
                        onOpenLogs = onOpenScrapeLogs,
                        onClearLogs = viewModel::clearScrapeLog
                    )

                    SettingsPage.Subtitle -> SubtitleSettingsPage(
                        uiState = uiState,
                        onOpenRealtimeSubtitle = { currentPage = SettingsPage.Translate },
                        onProviderSelected = viewModel::updateSubtitleSearchProvider
                    )

                    SettingsPage.Translate -> TranslateSettingsSummaryPage(
                        uiState = uiState,
                        onProviderSelected = viewModel::updateTranslateProvider,
                        onAsrModelSelected = viewModel::updateAsrModel,
                        onAsrModelBaseUrlChange = viewModel::updateAsrModelBaseUrl,
                        onDownloadAsrModel = viewModel::downloadAsrModel,
                        onOpenTranslateDialog = { showTranslateDialog = true }
                    )

                    SettingsPage.Player -> PlayerSettingsPage(
                        uiState = uiState,
                        onLiveSubtitleEnabledChange = viewModel::updatePlayerLiveSubtitleEnabled,
                        onExternalSubtitleFontSizeChange = viewModel::updateExternalSubtitleFontSizeSp,
                        onExternalSubtitleBottomPaddingChange = viewModel::updateExternalSubtitleBottomPaddingPercent,
                        onExternalSubtitleBackgroundAlphaChange = viewModel::updateExternalSubtitleBackgroundAlphaPercent
                    )
                }
                if (currentPage != null) {
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isScraping,
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Text("保存设置", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
        )

        if (showStrmBaseUrlDialog) {
            StrmBaseUrlDialog(
                value = uiState.strmBaseUrl,
                onValueChange = viewModel::updateBaseUrl,
                onDismiss = { showStrmBaseUrlDialog = false }
            )
        }
        if (showImageCacheDialog) {
            ClearImageCacheDialog(
                sizeText = imageCacheSizeText,
                onDismiss = { showImageCacheDialog = false },
                onConfirm = {
                    showImageCacheDialog = false
                    scope.launch {
                        val clearedBytes = withContext(Dispatchers.IO) {
                            MovieImageCacheStore.clear(context)
                        }
                        imageCacheSizeText = formatCacheSize(0L)
                        val snackbarJob = launch {
                            snackbarHostState.showSnackbar("已清理图片缓存：${formatCacheSize(clearedBytes)}")
                        }
                        delay(1_600)
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarJob.cancel()
                    }
                }
            )
        }
        if (showTranslateDialog) {
            TranslateSettingsDialog(
                provider = uiState.translateProvider,
                baiduAppId = uiState.baiduTranslateAppId,
                baiduSecretKey = uiState.baiduTranslateSecretKey,
                deepSeekApiKey = uiState.deepSeekApiKey,
                deepSeekBaseUrl = uiState.deepSeekBaseUrl,
                deepSeekModel = uiState.deepSeekModel,
                deepSeekThinkingEnabled = uiState.deepSeekThinkingEnabled,
                deepSeekPromptEnabled = uiState.deepSeekPromptEnabled,
                deepSeekPromptOptions = uiState.deepSeekPromptOptions,
                deepSeekPromptTemplateId = uiState.deepSeekPromptTemplateId,
                deepSeekCustomPrompt = uiState.deepSeekCustomPrompt,
                onBaiduAppIdChange = viewModel::updateBaiduTranslateAppId,
                onBaiduSecretKeyChange = viewModel::updateBaiduTranslateSecretKey,
                onDeepSeekApiKeyChange = viewModel::updateDeepSeekApiKey,
                onDeepSeekBaseUrlChange = viewModel::updateDeepSeekBaseUrl,
                onDeepSeekModelChange = viewModel::updateDeepSeekModel,
                onDeepSeekThinkingChange = viewModel::updateDeepSeekThinkingEnabled,
                onDeepSeekPromptEnabledChange = viewModel::updateDeepSeekPromptEnabled,
                onDeepSeekPromptTemplateChange = viewModel::updateDeepSeekPromptTemplate,
                onDeepSeekCustomPromptChange = viewModel::updateDeepSeekCustomPrompt,
                onDismiss = { showTranslateDialog = false },
                onSave = {
                    viewModel.saveBaiduTranslateSettings()
                    showTranslateDialog = false
                }
            )
        }
    }
}

private fun SettingsPage.titleText(): String = when (this) {
    SettingsPage.Directory -> "目录设置"
    SettingsPage.Cloud -> "网盘设置"
    SettingsPage.Scrape -> "刮削设置"
    SettingsPage.Subtitle -> "字幕设置"
    SettingsPage.Translate -> "实时字幕翻译"
    SettingsPage.Player -> "播放器设置"
}

@Composable
private fun SettingsOverviewPage(
    uiState: SettingsUiState,
    imageCacheSizeText: String,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenLogs: () -> Unit,
    onSave: () -> Unit
) {
    SettingsGroupCard(title = "目录") {
        SettingsEntryRow(
            title = "本地影片库与 STRM 目录",
            subtitle = "影片库：${uiState.libraryRootDisplayName}\nSTRM：${uiState.strmTreeDisplayName}",
            onClick = { onOpenPage(SettingsPage.Directory) }
        )
    }
    SettingsGroupCard(title = "网盘") {
        SettingsEntryRow(
            title = "115、STRM 入口与 A目录",
            subtitle = "115 账号登录与切换 · 国产页面：${if (uiState.domesticPageEnabled) "已开启" else "未开启"} · A目录 ${uiState.domesticRootCidText.ifBlank { "未配置" }}",
            onClick = { onOpenPage(SettingsPage.Cloud) }
        )
    }
    SettingsGroupCard(title = "刮削") {
        SettingsEntryRow(
            title = "默认刮削与图片缓存",
            subtitle = "${uiState.defaultScrapeSource.label} · 并发 ${uiState.scrapeConcurrencyLimitText} · 图片重试 ${uiState.imageDownloadRetryCountText} 次 · 缓存 $imageCacheSizeText",
            onClick = { onOpenPage(SettingsPage.Scrape) }
        )
        SettingsEntryRow(
            title = "刮削日志",
            subtitle = "查看每天的刮削事件和失败原因",
            onClick = onOpenLogs
        )
    }
    SettingsGroupCard(title = "字幕") {
        SettingsEntryRow(
            title = "字幕设置",
            subtitle = "实时字幕 · 在线字幕：${uiState.subtitleSearchProvider.label}",
            onClick = { onOpenPage(SettingsPage.Subtitle) }
        )
    }
    SettingsGroupCard(title = "播放器") {
        SettingsEntryRow(
            title = "播放器设置",
            subtitle = if (uiState.playerLiveSubtitleEnabled) {
                "实时字幕：已开启"
            } else {
                "实时字幕：未开启"
            },
            onClick = { onOpenPage(SettingsPage.Player) }
        )
    }
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isScraping,
        shape = RoundedCornerShape(22.dp)
    ) {
        Icon(Icons.Rounded.Save, contentDescription = null)
        Text("保存设置", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PlayerSettingsPage(
    uiState: SettingsUiState,
    onLiveSubtitleEnabledChange: (Boolean) -> Unit,
    onExternalSubtitleFontSizeChange: (Int) -> Unit,
    onExternalSubtitleBottomPaddingChange: (Int) -> Unit,
    onExternalSubtitleBackgroundAlphaChange: (Int) -> Unit
) {
    SettingsSectionTitle("实时字幕")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "开启实时字幕",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "关闭后，播放器里的实时字幕按钮不会启动 ASR；本地字幕和在线字幕不受影响。",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = uiState.playerLiveSubtitleEnabled,
            onCheckedChange = onLiveSubtitleEnabledChange
        )
    }

    SettingsSectionTitle("外挂字幕样式")
    SettingsGroupCard(title = "字幕显示") {
        SubtitleStyleSliderRow(
            title = "字号",
            value = uiState.externalSubtitleFontSizeSp,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
            valueText = "${uiState.externalSubtitleFontSizeSp}sp",
            onValueChange = onExternalSubtitleFontSizeChange
        )
        SubtitleStyleSliderRow(
            title = "底部位置",
            value = uiState.externalSubtitleBottomPaddingPercent,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
            valueText = "${uiState.externalSubtitleBottomPaddingPercent}%",
            onValueChange = onExternalSubtitleBottomPaddingChange
        )
        SubtitleStyleSliderRow(
            title = "背景不透明度",
            value = uiState.externalSubtitleBackgroundAlphaPercent,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
            valueText = "${uiState.externalSubtitleBackgroundAlphaPercent}%",
            onValueChange = onExternalSubtitleBackgroundAlphaChange
        )
    }
}

@Composable
private fun SubtitleStyleSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    valueText: String,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { next -> onValueChange(next.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
            )
        )
    }
}

@Composable
private fun SubtitleSettingsPage(
    uiState: SettingsUiState,
    onOpenRealtimeSubtitle: () -> Unit,
    onProviderSelected: (SubtitleSearchProvider) -> Unit
) {
    SettingsSectionTitle("字幕功能")
    SettingsGroupCard(title = "实时字幕") {
        SettingsEntryRow(
            title = "实时字幕翻译",
            subtitle = "播放器按钮：${if (uiState.playerLiveSubtitleEnabled) "已开启" else "未开启"} · 翻译：${uiState.translateProvider.label}",
            onClick = onOpenRealtimeSubtitle
        )
    }

    SettingsSectionTitle("在线字幕来源")
    SubtitleProviderRow(
        selected = uiState.subtitleSearchProvider,
        options = uiState.subtitleSearchProviderOptions,
        onSelected = onProviderSelected
    )
    Text(
        text = "播放器字幕按钮会先显示本地字幕；点击搜索时才使用这里选择的在线字幕来源。",
        color = Color.White.copy(alpha = 0.58f),
        style = MaterialTheme.typography.bodySmall
    )

    SettingsGroupCard(title = "Javzimu.com") {
        SubtitleSourceInfoRow(
            selected = uiState.subtitleSearchProvider == SubtitleSearchProvider.Javzimu,
            title = "Javzimu.com",
            subtitle = "使用 javzimu.com/search/番号 搜索并下载字幕。遇到验证时会沿用现有 WebView Cookie 流程。",
            onClick = { onProviderSelected(SubtitleSearchProvider.Javzimu) }
        )
    }
    SettingsGroupCard(title = "AVSubtitles") {
        SubtitleSourceInfoRow(
            selected = uiState.subtitleSearchProvider == SubtitleSearchProvider.Avsubtitles,
            title = "AVSubtitles",
            subtitle = "使用 avsubtitles.com 搜索影片详情页，再解析 subid/revid 下载 zip 字幕。",
            onClick = { onProviderSelected(SubtitleSearchProvider.Avsubtitles) }
        )
    }
    SettingsGroupCard(title = "迅雷字幕") {
        SubtitleSourceInfoRow(
            selected = uiState.subtitleSearchProvider == SubtitleSearchProvider.Xunlei,
            title = "迅雷字幕",
            subtitle = "使用迅雷字幕接口按番号搜索，返回的 srt/ass 字幕可直接下载并加载。",
            onClick = { onProviderSelected(SubtitleSearchProvider.Xunlei) }
        )
    }
}

@Composable
private fun SubtitleProviderRow(
    selected: SubtitleSearchProvider,
    options: List<SubtitleSearchProvider>,
    onSelected: (SubtitleSearchProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = selected.label,
                modifier = Modifier.weight(1f),
                color = Color.White
            )
            Text(text = "选择", color = Color.White.copy(alpha = 0.62f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        expanded = false
                        onSelected(provider)
                    }
                )
            }
        }
    }
}

@Composable
private fun SubtitleSourceInfoRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF76D48A)
            )
        }
    }
}

@Composable
private fun DirectorySettingsPage(
    uiState: SettingsUiState,
    onPickLibrary: () -> Unit,
    onPickStrmDirectory: () -> Unit,
    onNoMediaEnabledChange: (Boolean) -> Unit,
    onReorganize: () -> Unit,
    onRebuildIndex: () -> Unit
) {
    SettingsSectionTitle("影片库目录")
    DirectorySummary(
        title = uiState.libraryRootDisplayName,
        selected = uiState.libraryRootUri != null,
        emptyText = "尚未选择影片库目录"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "生成 .nomedia 文件屏蔽图片",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "开启后会在影片库目录创建 .nomedia，避免相册显示海报和剧照；关闭后删除该文件。",
                color = Color.White.copy(alpha = 0.56f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = uiState.libraryNoMediaEnabled,
            onCheckedChange = onNoMediaEnabledChange,
            enabled = uiState.libraryRootUri != null && !uiState.isScanning && !uiState.isReorganizing
        )
    }
    Button(
        onClick = onPickLibrary,
        enabled = !uiState.isScanning && !uiState.isReorganizing && !uiState.isScraping,
        shape = RoundedCornerShape(20.dp)
    ) {
        if (uiState.isScanning) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.Black)
        } else {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
        }
        Text(
            text = if (uiState.isScanning) "扫描中..." else "选择并扫描影片库",
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    OutlinedButton(
        onClick = onReorganize,
        enabled = !uiState.isScanning && !uiState.isReorganizing && !uiState.isScraping,
        shape = RoundedCornerShape(20.dp)
    ) {
        if (uiState.isReorganizing) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(Icons.Rounded.Article, contentDescription = null)
        }
        Text(
            text = if (uiState.isReorganizing) "正在重新整理..." else "重新整理当前影片库",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    Text(
        text = "按演员目录整理当前影片库，并重新扫描当前影片库。不会处理历史影片库目录。",
        color = Color.White.copy(alpha = 0.56f),
        style = MaterialTheme.typography.bodySmall
    )

    SettingsSectionTitle("STRM 保存位置")
    DirectorySummary(
        title = uiState.strmTreeDisplayName,
        selected = uiState.strmTreeUri != null,
        emptyText = "尚未选择 STRM 保存目录"
    )
    Button(
        onClick = onPickStrmDirectory,
        enabled = !uiState.isScraping,
        shape = RoundedCornerShape(20.dp)
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
        Text("选择 STRM 保存目录", modifier = Modifier.padding(start = 8.dp))
    }
    OutlinedButton(
        onClick = onRebuildIndex,
        enabled = !uiState.isScraping && !uiState.isRebuildingStrmIndex,
        shape = RoundedCornerShape(20.dp)
    ) {
        if (uiState.isRebuildingStrmIndex) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(Icons.Rounded.Article, contentDescription = null)
        }
        Text(
            text = if (uiState.isRebuildingStrmIndex) "正在重建索引..." else "重建 STRM 索引并规范分段命名",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun CloudSettingsPage(
    uiState: SettingsUiState,
    onCloud115AppSelected: (Cloud115LoginApp) -> Unit,
    onSavedCloud115AccountSelected: (SavedCloud115Account) -> Unit,
    onSavedCloud115AccountDelete: (SavedCloud115Account) -> Unit,
    onRefreshSavedCloud115Accounts: () -> Unit,
    onDomesticRootCidChange: (String) -> Unit,
    onDomesticPageEnabledChange: (Boolean) -> Unit,
    onStartCloud115QrLogin: () -> Unit,
    onCancelCloud115QrLogin: () -> Unit,
    onOpenStrmBaseUrlDialog: () -> Unit,
    onCloudAddButtonMessageEnabledChange: (Boolean) -> Unit,
    onExcludedVideoNameDraftChange: (String) -> Unit,
    onAddExcludedVideoName: () -> Unit,
    onRemoveExcludedVideoName: (String) -> Unit
) {
    SettingsSectionTitle("115 Cookie")
    Cloud115QrLoginPanel(
        uiState = uiState,
        onCloud115AppSelected = onCloud115AppSelected,
        onSavedCloud115AccountSelected = onSavedCloud115AccountSelected,
        onSavedCloud115AccountDelete = onSavedCloud115AccountDelete,
        onRefreshSavedCloud115Accounts = onRefreshSavedCloud115Accounts,
        onStartCloud115QrLogin = onStartCloud115QrLogin,
        onCancelCloud115QrLogin = onCancelCloud115QrLogin
    )
    SettingsSectionTitle("STRM 入口地址")
    OutlinedButton(
        onClick = onOpenStrmBaseUrlDialog,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            text = "点击设置 STRM 入口地址",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "已隐藏",
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
    }
    SettingsSectionTitle("A目录")
    OutlinedTextField(
        value = uiState.domesticRootCidText,
        onValueChange = onDomesticRootCidChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("A目录 CID") },
        placeholder = { Text("未配置时不启用国产目录") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    DomesticPageSwitchRow(
        enabled = uiState.domesticPageEnabled,
        onEnabledChange = onDomesticPageEnabledChange
    )
    SettingsSectionTitle("网盘添加")
    CloudAddBehaviorPanel(
        enabled = uiState.cloudAddButtonMessageEnabled,
        onEnabledChange = onCloudAddButtonMessageEnabledChange
    )
    SettingsSectionTitle("排除视频")
    ExcludedCloudVideosPanel(
        names = uiState.cloudExcludedVideoNames,
        draft = uiState.newExcludedVideoName,
        onDraftChange = onExcludedVideoNameDraftChange,
        onAdd = onAddExcludedVideoName,
        onRemove = onRemoveExcludedVideoName
    )
}

@Composable
private fun DomesticPageSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "开启国产页面",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "关闭后，影片页面不显示国产分类。默认关闭。",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

@Composable
private fun CloudAddBehaviorPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "开启按钮提示",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "关闭后，网盘点击添加不会弹出正在添加、已添加这类提示。",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

@Composable
private fun ExcludedCloudVideosPanel(
    names: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "排除视频名单",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已排除 ${names.size} 个视频。命中后仍可播放，但不显示添加按钮。",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("管理")
            }
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("排除视频名单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("名单中的视频不会显示添加按钮。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            label = { Text("视频文件名") },
                            placeholder = { Text("例如 广告.mp4") }
                        )
                        Button(
                            onClick = onAdd,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("添加")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (names.isEmpty()) {
                            Text("暂无排除视频")
                        } else {
                            names.forEach { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = { onRemove(name) }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun ScrapeSettingsPage(
    uiState: SettingsUiState,
    imageCacheSizeText: String,
    onSourceSelected: (ScrapeSource) -> Unit,
    onRetryCountChange: (String) -> Unit,
    onConcurrencyLimitChange: (String) -> Unit,
    onDmm2SkippedPrefixDraftChange: (String) -> Unit,
    onAddDmm2SkippedPrefix: () -> Unit,
    onRemoveDmm2SkippedPrefix: (String) -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearImageCache: () -> Unit,
    onOpenLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    SettingsSectionTitle("默认刮削")
    DefaultScrapeSourceRow(
        selected = uiState.defaultScrapeSource,
        onSelected = onSourceSelected
    )
    SettingsSectionTitle("DMM2 跳过")
    Dmm2SkippedPrefixPanel(
        prefixes = uiState.dmm2SkippedPrefixes,
        draft = uiState.newDmm2SkippedPrefix,
        onDraftChange = onDmm2SkippedPrefixDraftChange,
        onAdd = onAddDmm2SkippedPrefix,
        onRemove = onRemoveDmm2SkippedPrefix
    )
    SettingsSectionTitle("刮削队列")
    OutlinedTextField(
        value = uiState.scrapeConcurrencyLimitText,
        onValueChange = onConcurrencyLimitChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("同时刮削任务数") },
        supportingText = { Text("范围 1 到 ${AppSettingsRepository.MAX_SCRAPE_CONCURRENCY_LIMIT}。建议 2，过高可能导致请求失败或图片下载失败。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    SettingsSectionTitle("图片下载")
    OutlinedTextField(
        value = uiState.imageDownloadRetryCountText,
        onValueChange = onRetryCountChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("图片下载重试次数") },
        supportingText = { Text("建议 3 到 6 次。DMM 图片地址优先请求。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    ImageCachePanel(
        sizeText = imageCacheSizeText,
        onRefresh = onRefreshCacheSize,
        onClear = onClearImageCache
    )
    SettingsSectionTitle("刮削日志")
    Text(
        text = "未刮削 STRM 可以在影片详情页的“更多”中手动刮削；网盘添加影片时会使用默认刮削来源。",
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.bodySmall
    )
    ScrapeLogPanel(
        log = uiState.scrapeLog,
        onClear = onClearLogs,
        onOpenLogs = onOpenLogs
    )
}

@Composable
private fun Dmm2SkippedPrefixPanel(
    prefixes: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "跳过番号开头",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (prefixes.isEmpty()) {
                        "未设置。只影响 DMM2 刮削方式。"
                    } else {
                        "已设置 ${prefixes.size} 个：${prefixes.joinToString("、")}"
                    },
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("管理")
            }
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("DMM2 跳过番号开头") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("例如添加 ABF 后，DMM2 遇到 ABF-123 会直接跳过。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            label = { Text("番号开头") },
                            placeholder = { Text("例如 ABF") }
                        )
                        Button(
                            onClick = onAdd,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("添加")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prefixes.isEmpty()) {
                            Text("暂无跳过前缀")
                        } else {
                            prefixes.forEach { prefix ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = prefix,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = { onRemove(prefix) }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun TranslateSettingsSummaryPage(
    uiState: SettingsUiState,
    onProviderSelected: (TranslateProvider) -> Unit,
    onAsrModelSelected: (com.example.localmovielibrary.asr.AsrModelOption) -> Unit,
    onAsrModelBaseUrlChange: (String) -> Unit,
    onDownloadAsrModel: () -> Unit,
    onOpenTranslateDialog: () -> Unit
) {
    SettingsSectionTitle("翻译方式")
    TranslateProviderSelector(
        provider = uiState.translateProvider,
        onProviderSelected = onProviderSelected
    )
    Text(
        text = "只在这里选择翻译方式。密钥、模型和接口地址放到弹窗里配置，避免设置页堆太长。",
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(
        onClick = onOpenTranslateDialog,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(Icons.Rounded.Settings, contentDescription = null)
        Text("配置 ${uiState.translateProvider.label}", modifier = Modifier.padding(start = 8.dp))
    }
    if (uiState.translateProvider == TranslateProvider.DeepSeek) {
        val promptTemplate = uiState.deepSeekPromptOptions
            .firstOrNull { it.id == uiState.deepSeekPromptTemplateId }
        Text(
            text = "当前提示词：${promptTemplate?.label ?: uiState.deepSeekPromptTemplateId}",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
    }
    SettingsSectionTitle("本地 ASR 模型")
    AsrModelPanel(
        uiState = uiState,
        onModelSelected = onAsrModelSelected,
        onBaseUrlChange = onAsrModelBaseUrlChange,
        onDownload = onDownloadAsrModel
    )
}

@Composable
private fun TranslateProviderSelector(
    provider: TranslateProvider,
    onProviderSelected: (TranslateProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = provider.label,
                modifier = Modifier.weight(1f),
                color = Color.White
            )
            Text(text = "选择", color = Color.White.copy(alpha = 0.62f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TranslateProvider.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onProviderSelected(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun AsrModelPanel(
    uiState: SettingsUiState,
    onModelSelected: (com.example.localmovielibrary.asr.AsrModelOption) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onDownload: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = uiState.asrModelOptions.firstOrNull { it.id == uiState.selectedAsrModelId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = selected?.label ?: uiState.selectedAsrModelId,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("模型", color = Color.White.copy(alpha = 0.62f))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                uiState.asrModelOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onModelSelected(option)
                        }
                    )
                }
            }
        }
        selected?.description?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = if (uiState.isAsrModelReady) "状态：已下载，${uiState.asrModelSizeText}" else "状态：未下载",
            color = if (uiState.isAsrModelReady) Color(0xFF7BD88F) else Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = uiState.asrModelBaseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = 3,
            shape = RoundedCornerShape(18.dp),
            label = { Text("模型下载地址") },
            supportingText = { Text("目录地址即可，App 会下载 model.int8.onnx 和 tokens.txt。") },
            colors = settingsTextFieldColors()
        )
        if (uiState.isAsrModelDownloading || uiState.asrModelDownloadMessage.isNotBlank()) {
            LinearProgressIndicator(
                progress = { uiState.asrModelDownloadProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = uiState.asrModelDownloadMessage.ifBlank { "${uiState.asrModelDownloadProgress}%" },
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onDownload,
            enabled = !uiState.isAsrModelDownloading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(if (uiState.isAsrModelDownloading) "下载中..." else "下载/更新模型")
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(18.dp))
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ClearImageCacheDialog(
    sizeText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理图片缓存") },
        text = { Text("当前图片缓存约 $sizeText。确认清理后，海报和缩略图会在下次显示时重新缓存。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认清理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TranslateSettingsDialog(
    provider: TranslateProvider,
    baiduAppId: String,
    baiduSecretKey: String,
    deepSeekApiKey: String,
    deepSeekBaseUrl: String,
    deepSeekModel: String,
    deepSeekThinkingEnabled: Boolean,
    deepSeekPromptEnabled: Boolean,
    deepSeekPromptOptions: List<DeepSeekPromptTemplate>,
    deepSeekPromptTemplateId: String,
    deepSeekCustomPrompt: String,
    onBaiduAppIdChange: (String) -> Unit,
    onBaiduSecretKeyChange: (String) -> Unit,
    onDeepSeekApiKeyChange: (String) -> Unit,
    onDeepSeekBaseUrlChange: (String) -> Unit,
    onDeepSeekModelChange: (String) -> Unit,
    onDeepSeekThinkingChange: (Boolean) -> Unit,
    onDeepSeekPromptEnabledChange: (Boolean) -> Unit,
    onDeepSeekPromptTemplateChange: (DeepSeekPromptTemplate) -> Unit,
    onDeepSeekCustomPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var promptMenuExpanded by remember { mutableStateOf(false) }
    val selectedPromptTemplate = deepSeekPromptOptions.firstOrNull { it.id == deepSeekPromptTemplateId }
        ?: deepSeekPromptOptions.firstOrNull()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .background(Color(0xFF202126), RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "${provider.label} 配置",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ASR 识别出的文本会发送到所选翻译服务；音频不会发送给翻译接口。",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
            when (provider) {
                TranslateProvider.Baidu -> {
                    OutlinedTextField(
                        value = baiduAppId,
                        onValueChange = onBaiduAppIdChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("百度翻译 APP ID") },
                        colors = settingsTextFieldColors()
                    )
                    OutlinedTextField(
                        value = baiduSecretKey,
                        onValueChange = onBaiduSecretKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("百度翻译密钥") },
                        colors = settingsTextFieldColors()
                    )
                }

                TranslateProvider.DeepSeek -> {
                    OutlinedTextField(
                        value = deepSeekApiKey,
                        onValueChange = onDeepSeekApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("DeepSeek API Key") },
                        colors = settingsTextFieldColors()
                    )
                    OutlinedTextField(
                        value = deepSeekModel,
                        onValueChange = onDeepSeekModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("DeepSeek 模型") },
                        placeholder = { Text("deepseek-v4-flash") },
                        colors = settingsTextFieldColors()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "思考模式",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "实时字幕建议关闭，速度更快。",
                                color = Color.White.copy(alpha = 0.56f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = deepSeekThinkingEnabled,
                            onCheckedChange = onDeepSeekThinkingChange
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用翻译提示词",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "关闭后不会向大模型发送额外 system prompt。",
                                color = Color.White.copy(alpha = 0.56f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = deepSeekPromptEnabled,
                            onCheckedChange = onDeepSeekPromptEnabledChange
                        )
                    }
                    if (deepSeekPromptEnabled) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { promptMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = "提示词：${selectedPromptTemplate?.label.orEmpty()}",
                                    modifier = Modifier.weight(1f),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = promptMenuExpanded,
                                onDismissRequest = { promptMenuExpanded = false },
                                modifier = Modifier.background(Color(0xFF25272D))
                            ) {
                                deepSeekPromptOptions.forEach { template ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "${if (template.id == deepSeekPromptTemplateId) "✓ " else ""}${template.label}",
                                                color = Color.White
                                            )
                                        },
                                        onClick = {
                                            onDeepSeekPromptTemplateChange(template)
                                            promptMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (deepSeekPromptTemplateId == DeepSeekPromptTemplates.CUSTOM_ID) {
                            OutlinedTextField(
                                value = deepSeekCustomPrompt,
                                onValueChange = onDeepSeekCustomPromptChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 130.dp, max = 260.dp),
                                minLines = 5,
                                maxLines = 10,
                                shape = RoundedCornerShape(18.dp),
                                label = { Text("自定义翻译提示词") },
                                placeholder = { Text("输入 system prompt，只输出译文等规则可以写在这里") },
                                colors = settingsTextFieldColors()
                            )
                        }
                    }
                    OutlinedTextField(
                        value = deepSeekBaseUrl,
                        onValueChange = onDeepSeekBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("DeepSeek 接口地址") },
                        placeholder = { Text("https://api.deepseek.com") },
                        colors = settingsTextFieldColors()
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(18.dp)) {
                    Text("取消")
                }
                Button(onClick = onSave, shape = RoundedCornerShape(18.dp)) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun StrmBaseUrlDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF202126), RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "STRM 入口地址",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("http://127.0.0.1") },
                colors = settingsTextFieldColors()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { draft = "http://127.0.0.1" }, shape = RoundedCornerShape(18.dp)) {
                    Text("设为 127.0.0.1")
                }
                Button(
                    onClick = {
                        onValueChange(draft.ifBlank { "http://127.0.0.1" })
                        onDismiss()
                    },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun DefaultScrapeSourceRow(
    selected: ScrapeSource,
    onSelected: (ScrapeSource) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = selected.label,
                modifier = Modifier.weight(1f),
                color = Color.White
            )
            Text(text = "选择", color = Color.White.copy(alpha = 0.62f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ScrapeSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label) },
                    onClick = {
                        expanded = false
                        onSelected(source)
                    }
                )
            }
        }
    }
}

@Composable
private fun Cloud115QrLoginPanel(
    uiState: SettingsUiState,
    onCloud115AppSelected: (Cloud115LoginApp) -> Unit,
    onSavedCloud115AccountSelected: (SavedCloud115Account) -> Unit,
    onSavedCloud115AccountDelete: (SavedCloud115Account) -> Unit,
    onRefreshSavedCloud115Accounts: () -> Unit,
    onStartCloud115QrLogin: () -> Unit,
    onCancelCloud115QrLogin: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.82f)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = "115 二维码登录",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "扫码成功后会自动保存 Cookie，并写入文件 115cookie_userId_app.txt。",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        SavedCloud115AccountSelector(
            accounts = uiState.savedCloud115Accounts,
            selectedFileName = uiState.selectedCloud115AccountFileName,
            onSelected = onSavedCloud115AccountSelected,
            onDelete = onSavedCloud115AccountDelete,
            onRefresh = onRefreshSavedCloud115Accounts
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !uiState.isCloud115QrLoginActive,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = uiState.selectedCloud115LoginApp.description,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = "登录方式", color = Color.White.copy(alpha = 0.62f))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Cloud115LoginApps.all.forEach { app ->
                    DropdownMenuItem(
                        text = { Text(app.description) },
                        onClick = {
                            expanded = false
                            onCloud115AppSelected(app)
                        }
                    )
                }
            }
        }
        if (uiState.cloud115QrToken != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 260.dp)
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uiState.cloud115QrToken.qrImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "115 登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = "二维码地址：${uiState.cloud115QrToken.qrImageUrl}",
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.cloud115QrStatusText.isNotBlank()) {
            Text(
                text = uiState.cloud115QrStatusText,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.cloud115QrSavedFile?.let { path ->
            Text(
                text = "保存位置：$path",
                color = Color.White.copy(alpha = 0.48f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStartCloud115QrLogin,
                enabled = !uiState.isCloud115QrLoginActive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (uiState.cloud115QrToken == null) "获取二维码" else "重新获取")
            }
            if (uiState.isCloud115QrLoginActive) {
                OutlinedButton(
                    onClick = onCancelCloud115QrLogin,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("取消登录")
                }
            }
        }
    }
}

@Composable
private fun SavedCloud115AccountSelector(
    accounts: List<SavedCloud115Account>,
    selectedFileName: String?,
    onSelected: (SavedCloud115Account) -> Unit,
    onDelete: (SavedCloud115Account) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.fileName == selectedFileName }
    var pending by remember(accounts, selectedFileName) { mutableStateOf(selected) }
    var deleteTarget by remember { mutableStateOf<SavedCloud115Account?>(null) }
    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color(0xFF202126),
            title = { Text("删除 115 账号？", color = Color.White) },
            text = {
                Text(
                    text = "确定删除账号「${account.displayName}」的本地 Cookie 文件吗？删除后不会影响 115 网盘真实账号。",
                    color = Color.White.copy(alpha = 0.72f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        if (pending?.fileName == account.fileName) pending = null
                        onDelete(account)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = {
                        onRefresh()
                        expanded = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = selected?.displayName
                            ?: pending?.displayName
                            ?: if (accounts.isEmpty()) "暂无已保存账号" else "选择已保存账号",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = "账号", color = Color.White.copy(alpha = 0.62f))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (accounts.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("没有找到已保存 Cookie 文件") },
                            onClick = { expanded = false }
                        )
                    } else {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.displayName) },
                                onClick = {
                                    expanded = false
                                    pending = account
                                }
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("刷新")
            }
            OutlinedButton(
                onClick = { pending?.let { deleteTarget = it } },
                enabled = pending != null,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("删除")
            }
        }
        Button(
            onClick = { pending?.let(onSelected) },
            enabled = pending != null && pending?.fileName != selectedFileName,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(if (pending == null) "请选择账号" else "确定使用 ${pending?.displayName.orEmpty()}")
        }
        Text(
            text = "可识别 115cookie_a_ios.txt、115cookie_b_os_linux.txt 这类文件，并切换为当前网盘 Cookie。",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MissavCookieStatusCard(
    hasCookie: Boolean,
    onOpenMissavWeb: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (hasCookie) Icons.Rounded.CheckCircle else Icons.Rounded.Public,
                contentDescription = null,
                tint = if (hasCookie) Color(0xFF7BD88F) else Color.White.copy(alpha = 0.72f)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = if (hasCookie) "已获取 MissAV Cookie" else "尚未获取 MissAV Cookie",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (hasCookie) "后续 MissAV 刮削会自动携带 Cookie。" else "需要 MissAV 时可以先打开页面获取一次 Cookie。",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        OutlinedButton(
            onClick = onOpenMissavWeb,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.Public, contentDescription = null)
            Text(if (hasCookie) "刷新 MissAV Cookie" else "获取 MissAV Cookie", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ImageCachePanel(
    sizeText: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "图片缓存",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sizeText,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(18.dp)) {
                Text("刷新")
            }
        }
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
            Text("清理图片缓存", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun TranslateSettingsPanel(
    provider: TranslateProvider,
    baiduAppId: String,
    baiduSecretKey: String,
    deepSeekApiKey: String,
    deepSeekBaseUrl: String,
    deepSeekModel: String,
    deepSeekThinkingEnabled: Boolean,
    onProviderSelected: (TranslateProvider) -> Unit,
    onBaiduAppIdChange: (String) -> Unit,
    onBaiduSecretKeyChange: (String) -> Unit,
    onDeepSeekApiKeyChange: (String) -> Unit,
    onDeepSeekBaseUrlChange: (String) -> Unit,
    onDeepSeekModelChange: (String) -> Unit,
    onDeepSeekThinkingChange: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ASR 识别出的文本会发送到所选翻译服务；音频不会发送给翻译接口。",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = provider.label,
                    modifier = Modifier.weight(1f),
                    color = Color.White
                )
                Text(text = "选择", color = Color.White.copy(alpha = 0.62f))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                TranslateProvider.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            expanded = false
                            onProviderSelected(item)
                        }
                    )
                }
            }
        }

        when (provider) {
            TranslateProvider.Baidu -> {
                OutlinedTextField(
                    value = baiduAppId,
                    onValueChange = onBaiduAppIdChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("百度翻译 APP ID") },
                    colors = settingsTextFieldColors()
                )
                OutlinedTextField(
                    value = baiduSecretKey,
                    onValueChange = onBaiduSecretKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("百度翻译密钥") },
                    colors = settingsTextFieldColors()
                )
            }

            TranslateProvider.DeepSeek -> {
                OutlinedTextField(
                    value = deepSeekApiKey,
                    onValueChange = onDeepSeekApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("DeepSeek API Key") },
                    colors = settingsTextFieldColors()
                )
                OutlinedTextField(
                    value = deepSeekModel,
                    onValueChange = onDeepSeekModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("DeepSeek 模型") },
                    placeholder = { Text("deepseek-v4-flash") },
                    colors = settingsTextFieldColors()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "思考模式",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "实时字幕建议关闭，速度更快。",
                            color = Color.White.copy(alpha = 0.56f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = deepSeekThinkingEnabled,
                        onCheckedChange = onDeepSeekThinkingChange
                    )
                }
                OutlinedTextField(
                    value = deepSeekBaseUrl,
                    onValueChange = onDeepSeekBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("DeepSeek 接口地址") },
                    placeholder = { Text("https://api.deepseek.com") },
                    colors = settingsTextFieldColors()
                )
            }
        }

        OutlinedButton(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Text("保存翻译配置", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ScrapeLogPanel(
    log: String,
    onClear: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "最近日志",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onClear, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Text("清空", modifier = Modifier.padding(start = 6.dp))
            }
        }
        OutlinedButton(onClick = onOpenLogs, shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.Article, contentDescription = null)
            Text("查看按日期日志", modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            text = log.ifBlank { "暂无日志" },
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 220.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun SettingsTopBar(title: String = "设置", onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), SettingsBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回设置", tint = Color.White)
            }
        } else {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun DirectorySummary(title: String, selected: Boolean, emptyText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (selected) title else emptyText,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (selected) "已选择" else "未配置",
            color = Color.White.copy(alpha = 0.38f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.42f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
    focusedContainerColor = Color.White.copy(alpha = 0.08f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
    focusedLabelColor = Color.White.copy(alpha = 0.72f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.62f),
    focusedSupportingTextColor = Color.White.copy(alpha = 0.52f),
    unfocusedSupportingTextColor = Color.White.copy(alpha = 0.52f)
)

@Composable
private fun rememberTreePicker(onPicked: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onPicked(uri)
        }
    }

private fun formatCacheSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}

private val ScrapeSource.label: String
    get() = when (this) {
        ScrapeSource.Dmm -> "DMM"
        ScrapeSource.Dmm2 -> "DMM2"
        ScrapeSource.Official -> "Official"
        ScrapeSource.Javbus -> "JavBus"
        ScrapeSource.Missav -> "MissAV"
    }
