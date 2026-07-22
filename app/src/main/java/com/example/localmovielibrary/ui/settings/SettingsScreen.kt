package com.example.localmovielibrary.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrary.scraper.CustomJsonPathCandidate
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.ScrapedMovieInfo
import com.example.localmovielibrary.ui.shared.MovieImageCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SettingsBackground = Color(0xFF070A0E)

private enum class SettingsPage {
    Directory,
    Cloud,
    Scrape,
    CustomJsonScrape,
    NumberRecognition,
    ScrapeTasks,
    Player,
    StartupAnimation,
    Update
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenScrapeLogs: () -> Unit,
    openScrapeTasksPage: Boolean = false,
    openUpdatePage: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryDirectoryPicker = rememberTreePicker { uri -> viewModel.scanLibrary(uri) }
    val startupImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val copiedImageUri = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val imageFile = File(
                            context.filesDir,
                            "startup-animation-${System.currentTimeMillis()}.image"
                        )
                        imageFile.outputStream().use { output -> input.copyTo(output) }
                        imageFile.toURI().toString()
                    }
                }.getOrNull()
            }
            copiedImageUri?.let(viewModel::updateStartupAnimationImageUri)
        }
    }
    val overviewScrollState = rememberScrollState()
    val pageScrollState = rememberScrollState()
    val updateScrollState = rememberScrollState()
    var currentPage by rememberSaveable {
        mutableStateOf<SettingsPage?>(
            when {
                openScrapeTasksPage -> SettingsPage.ScrapeTasks
                openUpdatePage -> SettingsPage.Update
                else -> null
            }
        )
    }
    var showImageCacheDialog by remember { mutableStateOf(false) }
    var imageCacheSizeText by remember { mutableStateOf("计算中...") }
    var shouldScrollUpdateToBottom by remember { mutableStateOf(openUpdatePage) }
    val contentScrollState = when (currentPage) {
        null -> overviewScrollState
        SettingsPage.Update -> updateScrollState
        else -> pageScrollState
    }

    BackHandler(enabled = currentPage != null) {
        if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
            onBack()
        } else {
            currentPage = null
        }
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

    LaunchedEffect(openScrapeTasksPage) {
        if (openScrapeTasksPage) {
            currentPage = SettingsPage.ScrapeTasks
        }
    }

    LaunchedEffect(openUpdatePage) {
        if (openUpdatePage) {
            currentPage = SettingsPage.Update
            shouldScrollUpdateToBottom = true
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage == SettingsPage.Cloud) {
            viewModel.refreshSavedCloud115Accounts()
        }
    }

    LaunchedEffect(currentPage) {
        when (currentPage) {
            SettingsPage.Update -> shouldScrollUpdateToBottom = true
            null -> Unit
            else -> pageScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(currentPage, shouldScrollUpdateToBottom) {
        if (currentPage != SettingsPage.Update || !shouldScrollUpdateToBottom) return@LaunchedEffect
        snapshotFlow { updateScrollState.maxValue }.collect { maxValue ->
            if (maxValue > 0) {
                updateScrollState.scrollTo(maxValue)
                shouldScrollUpdateToBottom = false
            }
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
                onBack = currentPage?.let {
                    if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
                        onBack
                    } else {
                        { currentPage = null }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState)
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
                        onNoMediaEnabledChange = viewModel::updateLibraryNoMediaEnabled,
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
                        onCloudAddButtonMessageEnabledChange = viewModel::updateCloudAddButtonMessageEnabled,
                        onCloudDeleteEnabledChange = viewModel::updateCloudDeleteEnabled,
                        onExcludedVideoNameDraftChange = viewModel::updateNewExcludedVideoName,
                        onAddExcludedVideoName = viewModel::addExcludedVideoName,
                        onRemoveExcludedVideoName = viewModel::removeExcludedVideoName,
                        onCloudScrapeSkipBelowSizeMbChange = viewModel::updateCloudScrapeSkipBelowSizeMb
                    )

                    SettingsPage.Scrape -> ScrapeSettingsPage(
                        uiState = uiState,
                        imageCacheSizeText = imageCacheSizeText,
                        onOpenNumberRules = { currentPage = SettingsPage.NumberRecognition },
                        onOpenCustomJsonScrape = { currentPage = SettingsPage.CustomJsonScrape },
                        onAddPrioritySource = viewModel::addPriorityScrapeSource,
                        onRemovePrioritySource = viewModel::removePriorityScrapeSource,
                        onMovePrioritySourceUp = viewModel::movePriorityScrapeSourceUp,
                        onMovePrioritySourceDown = viewModel::movePriorityScrapeSourceDown,
                        onRetryCountChange = viewModel::updateImageDownloadRetryCount,
                        onConcurrencyLimitChange = viewModel::updateScrapeConcurrencyLimit,
                        onScrapeProxyAddressChange = viewModel::updateScrapeProxyAddress,
                        onScrapeProxyEnabledChange = viewModel::updateScrapeProxyEnabled,
                        onDmm2SkippedPrefixDraftChange = viewModel::updateNewDmm2SkippedPrefix,
                        onAddDmm2SkippedPrefix = viewModel::addDmm2SkippedPrefix,
                        onRemoveDmm2SkippedPrefix = viewModel::removeDmm2SkippedPrefix,
                        onRemoteScrapeConfigUrlChange = viewModel::updateRemoteScrapeConfigUrl,
                        onMgstagePrefixDraftChange = viewModel::updateNewMgstagePrefix,
                        onMgstageNumericPrefixDraftChange = viewModel::updateNewMgstageNumericPrefix,
                        onAddMgstagePrefix = viewModel::addCustomMgstagePrefix,
                        onRemoveMgstagePrefix = viewModel::removeCustomMgstagePrefix,
                        onRefreshMgstageRules = viewModel::refreshMgstageRules,
                        onRefreshCacheSize = ::refreshImageCacheSize,
                        onClearImageCache = { showImageCacheDialog = true }
                    )

                    SettingsPage.NumberRecognition -> NumberRecognitionRulesPage(
                        uiState = uiState,
                        onRefreshRules = viewModel::refreshMgstageRules
                    )

                    SettingsPage.CustomJsonScrape -> CustomJsonScrapePage(
                        uiState = uiState,
                        onConfigChange = viewModel::updateCustomJsonScrapeConfig,
                        onSelectConfig = viewModel::selectCustomJsonScrapeConfig,
                        onAddConfig = viewModel::addCustomJsonScrapeConfig,
                        onDuplicateConfig = viewModel::duplicateCustomJsonScrapeConfig,
                        onDeleteConfig = viewModel::deleteCustomJsonScrapeConfig,
                        onTestConfig = viewModel::testCustomJsonScrapeConfig
                    )

                    SettingsPage.ScrapeTasks -> ScrapeTasksPage(
                        uiState = uiState,
                        onStartManualScrapeTasks = viewModel::startManualScrapeTasks,
                        onStopManualScrapeTasks = viewModel::stopManualScrapeTasks,
                        onCancelManualScrapeTasks = viewModel::cancelManualScrapeTasks,
                        onRefreshScrapeTasks = viewModel::refreshScrapeTaskSummary,
                        onResetFailedScrapeTasks = viewModel::resetFailedScrapeTasks,
                        onStartCloudFolderBatchTasks = viewModel::startCloudFolderBatchTasks,
                        onStopCloudFolderBatchTasks = viewModel::stopCloudFolderBatchTasks,
                        onCancelCloudFolderBatchTasks = viewModel::cancelCloudFolderBatchTasks,
                        onRefreshCloudFolderBatchTasks = viewModel::refreshCloudFolderBatchTasks
                    )

                    SettingsPage.Player -> PlayerSettingsPage(
                        uiState = uiState,
                        onSeekBackSecondsChange = viewModel::updatePlayerSeekBackSeconds,
                        onSeekForwardSecondsChange = viewModel::updatePlayerSeekForwardSeconds,
                        onExternalSubtitleFontSizeChange = viewModel::updateExternalSubtitleFontSizeSp,
                        onExternalSubtitleBottomPaddingChange = viewModel::updateExternalSubtitleBottomPaddingPercent,
                        onExternalSubtitleBackgroundAlphaChange = viewModel::updateExternalSubtitleBackgroundAlphaPercent
                    )

                    SettingsPage.StartupAnimation -> StartupAnimationSettingsPage(
                        uiState = uiState,
                        onEnabledChange = viewModel::updateStartupAnimationEnabled,
                        onPickImage = {
                            startupImagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onClearImage = viewModel::clearStartupAnimationImage
                    )

                    SettingsPage.Update -> AppUpdateSettingsPage(
                        uiState = uiState,
                        onManifestUrlChange = viewModel::updateManifestUrl,
                        onProxyBaseUrlChange = viewModel::updateProxyBaseUrl,
                        onUseProxyChange = viewModel::updateUseUpdateProxyEnabled,
                        onAutoCheckStartupChange = viewModel::updateAutoCheckUpdateOnStartupEnabled,
                        onAutoDeleteApkChange = viewModel::updateAutoDeleteInstalledUpdateApkEnabled,
                        onCheckUpdate = viewModel::checkForAppUpdate,
                        onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
                        onInstallDownloaded = viewModel::installDownloadedUpdate
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
        uiState.customJsonTestResult?.let { result ->
            CustomJsonMappingDialog(
                result = result,
                config = uiState.customJsonScrapeConfig,
                pathCandidates = uiState.customJsonPathCandidates,
                onConfigChange = viewModel::updateCustomJsonScrapeConfig,
                onDismiss = viewModel::dismissCustomJsonTestResult
            )
        }
    }
}

@Composable
private fun CustomJsonMappingDialog(
    result: ScrapedMovieInfo,
    config: CustomJsonScrapeConfig,
    pathCandidates: List<CustomJsonPathCandidate>,
    onConfigChange: (CustomJsonScrapeConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = remember(config) { customJsonMappingTargets(config) }
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var pathSegments by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var targetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, targets.lastIndex.coerceAtLeast(0))
    val selectedTarget = targets.getOrNull(safeIndex)
    val selectedSampleValue = selectedTarget?.value?.let { pathCandidates.sampleValue(it) }
    val currentPath = pathSegments.toJsonPath()
    val nodes = remember(pathCandidates, pathSegments) { pathCandidates.childNodes(pathSegments) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111820),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("配置字段映射") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "测试成功：" + result.title.ifBlank { result.number },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "先选字段，再从 JSON 树逐级进入分支；点叶子节点后会自动绑定并跳到下一个字段。",
                    style = MaterialTheme.typography.bodySmall
                )
                CustomJsonMappingPreview(config)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedTarget?.let { "当前字段：${it.label}" } ?: "当前字段",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = selectedTarget?.value?.ifBlank { "未选择" }.orEmpty(),
                                color = if (selectedTarget?.value.isNullOrBlank()) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!selectedSampleValue.isNullOrBlank()) {
                                Text(
                                    text = "样本值：$selectedSampleValue",
                                    color = Color.White.copy(alpha = 0.62f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box {
                            OutlinedButton(
                                onClick = { targetMenuExpanded = true },
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("切换字段")
                            }
                            DropdownMenu(
                                expanded = targetMenuExpanded,
                                onDismissRequest = { targetMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 360.dp),
                                containerColor = Color(0xFF17202B),
                            ) {
                                targets.forEachIndexed { index, target ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(target.label, color = Color.White, fontWeight = FontWeight.Bold)
                                                Text(
                                                    target.value.ifBlank { "未选择" },
                                                    color = Color.White.copy(alpha = 0.62f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedIndex = index
                                            pathSegments = emptyList()
                                            targetMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = selectedTarget?.let { "给「${it.label}」选择路径" } ?: "选择路径",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentPath,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = { pathSegments = pathSegments.dropLast(1) },
                        enabled = pathSegments.isNotEmpty(),
                    ) {
                        Text("上一级")
                    }
                }
                if (pathCandidates.isEmpty()) {
                    Text("没有生成可选路径，请检查结果路径是否正确。", color = MaterialTheme.colorScheme.error)
                } else if (nodes.isEmpty()) {
                    Text("这个分支下面没有可选字段。", color = MaterialTheme.colorScheme.error)
                } else {
                    nodes.forEach { node ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (node.hasChildren) {
                                        pathSegments = node.segments
                                    } else {
                                        selectedTarget?.let { target ->
                                            onConfigChange(target.apply(config, node.path))
                                            pathSegments = emptyList()
                                            if (safeIndex < targets.lastIndex) selectedIndex = safeIndex + 1
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = node.label.jsonSegmentDisplayLabel(),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (node.hasChildren) "进入" else "选择",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(node.path, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (node.valuePreview.isNotBlank()) {
                                Text(
                                    node.valuePreview,
                                    color = Color.White.copy(alpha = 0.62f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun CustomJsonMappingPreview(config: CustomJsonScrapeConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("当前结果路径：${config.resultPath}", style = MaterialTheme.typography.bodySmall)
        Text("提示：如果想选评分 $.reviewSummary.average，请把结果路径设到包含 reviewSummary 的节点后重新测试。", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.62f))
    }
}

private data class CustomJsonMappingTarget(
    val label: String,
    val value: String,
    val apply: (CustomJsonScrapeConfig, String) -> CustomJsonScrapeConfig
)

private data class CustomJsonPathNode(
    val label: String,
    val path: String,
    val segments: List<String>,
    val valuePreview: String,
    val hasChildren: Boolean,
)

private fun customJsonMappingTargets(config: CustomJsonScrapeConfig): List<CustomJsonMappingTarget> = listOf(
    CustomJsonMappingTarget("番号", config.numberPath) { current, path -> current.copy(numberPath = path) },
    CustomJsonMappingTarget("标题", config.titlePath) { current, path -> current.copy(titlePath = path) },
    CustomJsonMappingTarget("原始标题", config.originalTitlePath) { current, path -> current.copy(originalTitlePath = path) },
    CustomJsonMappingTarget("简介", config.plotPath) { current, path -> current.copy(plotPath = path) },
    CustomJsonMappingTarget("发行日期", config.premieredPath) { current, path -> current.copy(premieredPath = path) },
    CustomJsonMappingTarget("时长", config.runtimePath) { current, path -> current.copy(runtimePath = path) },
    CustomJsonMappingTarget("厂商", config.studioPath) { current, path -> current.copy(studioPath = path) },
    CustomJsonMappingTarget("系列", config.seriesPath) { current, path -> current.copy(seriesPath = path) },
    CustomJsonMappingTarget("演员", config.actorsPath) { current, path -> current.copy(actorsPath = path) },
    CustomJsonMappingTarget("类型", config.genresPath) { current, path -> current.copy(genresPath = path) },
    CustomJsonMappingTarget("标签", config.tagsPath) { current, path -> current.copy(tagsPath = path) },
    CustomJsonMappingTarget("评分", config.ratingPath) { current, path -> current.copy(ratingPath = path) },
    CustomJsonMappingTarget("Poster", config.posterPath) { current, path -> current.copy(posterPath = path) },
    CustomJsonMappingTarget("Thumb", config.thumbPath) { current, path -> current.copy(thumbPath = path) },
    CustomJsonMappingTarget("Fanart", config.fanartPath) { current, path -> current.copy(fanartPath = path) },
)

private fun List<CustomJsonPathCandidate>.childNodes(prefixSegments: List<String>): List<CustomJsonPathNode> {
    return asSequence()
        .map { candidate -> candidate to candidate.path.jsonPathSegments() }
        .filter { (_, segments) -> segments.size > prefixSegments.size && segments.take(prefixSegments.size) == prefixSegments }
        .groupBy { (_, segments) -> segments[prefixSegments.size] }
        .map { (segment, entries) ->
            val nodeSegments = prefixSegments + segment
            val exact = entries.firstOrNull { (_, segments) -> segments == nodeSegments }?.first
            val firstPreview = exact?.valuePreview
                ?: entries.firstOrNull { it.first.valuePreview.isNotBlank() }?.first?.valuePreview
                ?: ""
            CustomJsonPathNode(
                label = segment,
                path = nodeSegments.toJsonPath(),
                segments = nodeSegments,
                valuePreview = firstPreview,
                hasChildren = entries.any { (_, segments) -> segments.size > nodeSegments.size },
            )
        }
        .sortedWith(compareBy<CustomJsonPathNode> { !it.hasChildren }.thenBy { it.label.lowercase() })
        .toList()
}

private fun List<CustomJsonPathCandidate>.sampleValue(path: String): String =
    firstOrNull { it.path == path }?.valuePreview.orEmpty()

private fun String.jsonSegmentDisplayLabel(): String = when (this) {
    "[*]" -> "数组全部 [*]"
    else -> this
}

private fun String.jsonPathSegments(): List<String> =
    removePrefix("$.")
        .removePrefix("$")
        .trimStart('.')
        .split('.')
        .filter { it.isNotBlank() }

private fun List<String>.toJsonPath(): String =
    if (isEmpty()) "$" else "$." + joinToString(".")

@Composable
private fun CustomJsonTestResultDialog(
    result: ScrapedMovieInfo,
    requestedNumber: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 JSON 测试结果") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "请检查下面字段是否和接口返回一致。除番号可使用输入值兜底外，缺失字段会标记为缺失。",
                    style = MaterialTheme.typography.bodySmall
                )
                CustomJsonTestResultRow("番号", result.number, if (result.number == requestedNumber) "使用输入番号" else null)
                CustomJsonTestResultRow("标题", result.title)
                CustomJsonTestResultRow("原始标题", result.originalTitle)
                CustomJsonTestResultRow("简介", result.plot)
                CustomJsonTestResultRow("发行日期", result.premiered)
                CustomJsonTestResultRow("时长", result.runtime)
                CustomJsonTestResultRow("厂商", result.studio)
                CustomJsonTestResultRow("系列", result.series)
                CustomJsonTestResultRow("演员", result.actors.joinToString(", "))
                CustomJsonTestResultRow("类型", result.genres.joinToString(", "))
                CustomJsonTestResultRow("标签", result.tags.joinToString(", "))
                CustomJsonTestResultRow("评分", result.rating)
                CustomJsonTestResultRow("Poster", result.posterUrl)
                CustomJsonTestResultRow("Thumb", result.thumbUrl)
                CustomJsonTestResultRow("Fanart", result.fanartUrl)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

@Composable
private fun CustomJsonTestResultRow(
    label: String,
    value: String,
    note: String? = null
) {
    val missing = value.isBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = note ?: if (missing) "缺失" else "已匹配",
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = value.ifBlank { "未取得数据" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun SettingsPage.titleText(): String = when (this) {
    SettingsPage.Directory -> "目录设置"
    SettingsPage.Cloud -> "网盘设置"
    SettingsPage.Scrape -> "刮削设置"
    SettingsPage.CustomJsonScrape -> "自定义 JSON 刮削"
    SettingsPage.NumberRecognition -> "番号识别规则"
    SettingsPage.ScrapeTasks -> "刮削任务"
    SettingsPage.Player -> "播放器设置"
    SettingsPage.StartupAnimation -> "开场动画"
    SettingsPage.Update -> "应用更新"
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
            title = "本地影片库目录",
            subtitle = "影片库与 STRM 保存共用：${uiState.libraryRootDisplayName}",
            onClick = { onOpenPage(SettingsPage.Directory) }
        )
    }
    SettingsGroupCard(title = "网盘") {
        SettingsEntryRow(
            title = "115 与 A目录",
            subtitle = "115 账号登录与切换 · 国产页面：${if (uiState.domesticPageEnabled) "已开启" else "未开启"} · A目录 ${uiState.domesticRootCidText.ifBlank { "未配置" }}",
            onClick = { onOpenPage(SettingsPage.Cloud) }
        )
    }
    SettingsGroupCard(title = "刮削") {
        val movieTaskCount = uiState.scrapeTaskSummary.unfinished
        val folderTaskCount = uiState.cloudFolderBatchTasks.count {
            it.status != CloudFolderBatchTaskStatus.Completed.name
        }
        SettingsEntryRow(
            title = "默认刮削与图片缓存",
            subtitle = "${uiState.defaultScrapeSource.label} · 并发 ${uiState.scrapeConcurrencyLimitText} · 图片重试 ${uiState.imageDownloadRetryCountText} 次 · 缓存 $imageCacheSizeText",
            onClick = { onOpenPage(SettingsPage.Scrape) }
        )
        SettingsEntryRow(
            title = "番号识别规则",
            subtitle = "忽略后缀 ${uiState.numberRecognitionIgnoredSuffixes.size} · 分段标记 ${uiState.numberRecognitionPartMarkers.size}",
            onClick = { onOpenPage(SettingsPage.NumberRecognition) }
        )
        SettingsEntryRow(
            title = "刮削日志",
            subtitle = "查看每天的刮削事件和失败原因",
            onClick = onOpenLogs
        )
        SettingsEntryRow(
            title = "刮削任务",
            subtitle = if (movieTaskCount + folderTaskCount > 0) {
                "影片待处理 $movieTaskCount · 网盘文件夹待处理 $folderTaskCount"
            } else {
                "查看并手动启动影片刮削与网盘文件夹任务"
            },
            onClick = { onOpenPage(SettingsPage.ScrapeTasks) }
        )
    }
    SettingsGroupCard(title = "播放器") {
        SettingsEntryRow(
            title = "播放器设置",
            subtitle = "外挂字幕字号 ${uiState.externalSubtitleFontSizeSp}sp · 位置 ${uiState.externalSubtitleBottomPaddingPercent}%",
            onClick = { onOpenPage(SettingsPage.Player) }
        )
    }
    SettingsGroupCard(title = "应用") {
        SettingsEntryRow(
            title = "开场动画",
            subtitle = if (uiState.startupAnimationEnabled) "已开启 · ${if (uiState.startupAnimationImageUri.isBlank()) "默认画面" else "自定义图片"}" else "默认关闭",
            onClick = { onOpenPage(SettingsPage.StartupAnimation) }
        )
        SettingsEntryRow(
            title = "应用更新",
            subtitle = "当前版本 ${uiState.appVersionName.ifBlank { "未知" }} · ${if (uiState.updateManifestUrl.isBlank()) "未配置更新地址" else "已配置更新地址"}",
            onClick = { onOpenPage(SettingsPage.Update) }
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
private fun StartupAnimationSettingsPage(
    uiState: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    SettingsSectionTitle("开场动画")
    SettingsGroupCard(title = "启动过场") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("启用开场动画", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("进入应用时显示图片和加载动画，默认关闭。", color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.startupAnimationEnabled, onCheckedChange = onEnabledChange)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("动画图片", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (uiState.startupAnimationImageUri.isBlank()) {
                Text("未选择时将显示内置深色渐变画面。", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(uiState.startupAnimationImageUri)).crossfade(true).build(),
                    contentDescription = "开场动画预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPickImage) { Text("选择图片") }
                if (uiState.startupAnimationImageUri.isNotBlank()) {
                    TextButton(onClick = onClearImage) { Text("恢复默认") }
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsPage(
    uiState: SettingsUiState,
    onSeekBackSecondsChange: (Int) -> Unit,
    onSeekForwardSecondsChange: (Int) -> Unit,
    onExternalSubtitleFontSizeChange: (Int) -> Unit,
    onExternalSubtitleBottomPaddingChange: (Int) -> Unit,
    onExternalSubtitleBackgroundAlphaChange: (Int) -> Unit
) {
    SettingsSectionTitle("播放控制")
    SettingsGroupCard(title = "快进与后退") {
        SubtitleStyleSliderRow(
            title = "后退时间",
            value = uiState.playerSeekBackSeconds,
            range = AppSettingsRepository.MIN_PLAYER_SEEK_SECONDS..AppSettingsRepository.MAX_PLAYER_SEEK_SECONDS,
            valueText = "${uiState.playerSeekBackSeconds} 秒",
            onValueChange = onSeekBackSecondsChange
        )
        SubtitleStyleSliderRow(
            title = "快进时间",
            value = uiState.playerSeekForwardSeconds,
            range = AppSettingsRepository.MIN_PLAYER_SEEK_SECONDS..AppSettingsRepository.MAX_PLAYER_SEEK_SECONDS,
            valueText = "${uiState.playerSeekForwardSeconds} 秒",
            onValueChange = onSeekForwardSecondsChange
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
private fun AppUpdateSettingsPage(
    uiState: SettingsUiState,
    onManifestUrlChange: (String) -> Unit,
    onProxyBaseUrlChange: (String) -> Unit,
    onUseProxyChange: (Boolean) -> Unit,
    onAutoCheckStartupChange: (Boolean) -> Unit,
    onAutoDeleteApkChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallDownloaded: () -> Unit
) {
    val update = uiState.latestAppUpdate
    SettingsSectionTitle("当前版本")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "家庭电影院 ${uiState.appVersionName.ifBlank { "未知" }}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "versionCode ${uiState.appVersionCode}",
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
    }

    SettingsSectionTitle("更新配置")
    OutlinedTextField(
        value = uiState.updateManifestUrl,
        onValueChange = onManifestUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("版本信息地址") },
        placeholder = { Text(AppSettingsRepository.DEFAULT_UPDATE_MANIFEST_URL) },
        supportingText = { Text("默认使用 GitHub Releases 最新版本中的 latest.json。") },
        colors = settingsTextFieldColors()
    )
    OutlinedTextField(
        value = uiState.updateProxyBaseUrl,
        onValueChange = onProxyBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("GitHub 代理地址") },
        placeholder = { Text(AppSettingsRepository.DEFAULT_UPDATE_PROXY_BASE_URL) },
        supportingText = { Text("开启代理更新时使用；关闭后会直接访问 GitHub。") },
        colors = settingsTextFieldColors()
    )
    UpdateSwitchRow(
        title = "使用代理更新",
        subtitle = "开启后检查版本和下载 APK 会经过上面的代理地址；关闭后直接访问 GitHub。",
        checked = uiState.useUpdateProxyEnabled,
        onCheckedChange = onUseProxyChange
    )
    UpdateSwitchRow(
        title = "启动 App 后自动检测更新",
        subtitle = "开启后每次重新打开 App，会在 8 秒后静默检查；只有发现新版本才提示。",
        checked = uiState.autoCheckUpdateOnStartupEnabled,
        onCheckedChange = onAutoCheckStartupChange
    )
    UpdateSwitchRow(
        title = "安装新版本后自动删除旧的 APK",
        subtitle = "开启后，成功安装新版本并再次启动 App 时，会删除缓存目录里的更新 APK。",
        checked = uiState.autoDeleteInstalledUpdateApkEnabled,
        onCheckedChange = onAutoDeleteApkChange
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "APK 缓存位置",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiState.updateApkDirectoryPath.ifBlank { "暂未创建缓存目录" },
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = uiState.downloadedUpdateApkPath.ifBlank { "当前没有已下载的更新 APK" },
            color = Color.White.copy(alpha = 0.48f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = update?.let { "最新版本 ${it.versionName} · versionCode ${it.versionCode}" } ?: "尚未检查更新",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        update?.sizeBytes?.let { size ->
            Text(
                text = "APK 大小：${formatCacheSize(size)}",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (update != null && update.notes.isNotEmpty()) {
            Text(
                text = update.notes.take(5).joinToString("\n") { "- $it" },
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.updateMessage.isNotBlank()) {
            Text(
                text = uiState.updateMessage,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.isDownloadingUpdate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "${uiState.updateDownloadProgress.coerceIn(0, 100)}%",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onCheckUpdate,
            enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text(
                text = if (uiState.isCheckingUpdate) "正在检查" else "检查更新",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (uiState.hasAppUpdate) {
            Button(
                onClick = onDownloadAndInstall,
                enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Text(
                    text = if (uiState.isDownloadingUpdate) "正在下载" else "下载并安装",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (uiState.downloadedUpdateReady) {
            OutlinedButton(
                onClick = onInstallDownloaded,
                enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("安装已下载 APK")
            }
        }
    }
}

@Composable
private fun UpdateSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun DirectorySettingsPage(
    uiState: SettingsUiState,
    onPickLibrary: () -> Unit,
    onNoMediaEnabledChange: (Boolean) -> Unit,
    onRebuildIndex: () -> Unit
) {
    SettingsSectionTitle("影片库与 STRM 目录")
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
            enabled = uiState.libraryRootUri != null && !uiState.isScanning
        )
    }
    Button(
        onClick = onPickLibrary,
        enabled = !uiState.isScanning && !uiState.isScraping,
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
    onCloudAddButtonMessageEnabledChange: (Boolean) -> Unit,
    onCloudDeleteEnabledChange: (Boolean) -> Unit,
    onExcludedVideoNameDraftChange: (String) -> Unit,
    onAddExcludedVideoName: () -> Unit,
    onRemoveExcludedVideoName: (String) -> Unit,
    onCloudScrapeSkipBelowSizeMbChange: (String) -> Unit
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
        onEnabledChange = onCloudAddButtonMessageEnabledChange,
        cloudDeleteEnabled = uiState.cloudDeleteEnabled,
        onCloudDeleteEnabledChange = onCloudDeleteEnabledChange
    )
    SettingsSectionTitle("排除视频")
    ExcludedCloudVideosPanel(
        names = uiState.cloudExcludedVideoNames,
        draft = uiState.newExcludedVideoName,
        onDraftChange = onExcludedVideoNameDraftChange,
        onAdd = onAddExcludedVideoName,
        onRemove = onRemoveExcludedVideoName
    )
    OutlinedTextField(
        value = uiState.cloudScrapeSkipBelowSizeMbText,
        onValueChange = onCloudScrapeSkipBelowSizeMbChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("小文件跳过刮削阈值（MB）") },
        supportingText = { Text("文件夹批量添加时，小于或等于该大小的视频会跳过。填 0 表示关闭大小排除。默认 100MB。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
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
    onEnabledChange: (Boolean) -> Unit,
    cloudDeleteEnabled: Boolean,
    onCloudDeleteEnabledChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "开启网盘删除",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "开启后，影片详情删除弹窗会显示“删除(网盘)”，同时删除 115 网盘真实视频。默认关闭。",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = cloudDeleteEnabled,
                onCheckedChange = onCloudDeleteEnabledChange
            )
        }
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
    onOpenNumberRules: () -> Unit,
    onOpenCustomJsonScrape: () -> Unit,
    onAddPrioritySource: (ScrapeSource) -> Unit,
    onRemovePrioritySource: (ScrapeSource) -> Unit,
    onMovePrioritySourceUp: (ScrapeSource) -> Unit,
    onMovePrioritySourceDown: (ScrapeSource) -> Unit,
    onRetryCountChange: (String) -> Unit,
    onConcurrencyLimitChange: (String) -> Unit,
    onScrapeProxyAddressChange: (String) -> Unit,
    onScrapeProxyEnabledChange: (Boolean) -> Unit,
    onDmm2SkippedPrefixDraftChange: (String) -> Unit,
    onAddDmm2SkippedPrefix: () -> Unit,
    onRemoveDmm2SkippedPrefix: (String) -> Unit,
    onRemoteScrapeConfigUrlChange: (String) -> Unit,
    onMgstagePrefixDraftChange: (String) -> Unit,
    onMgstageNumericPrefixDraftChange: (String) -> Unit,
    onAddMgstagePrefix: () -> Unit,
    onRemoveMgstagePrefix: (String) -> Unit,
    onRefreshMgstageRules: () -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearImageCache: () -> Unit
) {
    SettingsSectionTitle("默认刮削（优先级）")
    PriorityScrapeSourcePanel(
        sources = uiState.priorityScrapeSources,
        options = uiState.priorityScrapeSourceOptions,
        onAdd = onAddPrioritySource,
        onRemove = onRemovePrioritySource,
        onMoveUp = onMovePrioritySourceUp,
        onMoveDown = onMovePrioritySourceDown
    )
    SettingsSectionTitle("自定义 JSON 刮削")
    CustomJsonScrapeEntryCard(
        config = uiState.customJsonScrapeConfig,
        onClick = onOpenCustomJsonScrape
    )
    SettingsSectionTitle("MGStage 刮削")
    MgstagePrefixPanel(
        customPrefixes = uiState.mgstageCustomPrefixes,
        remotePrefixes = uiState.mgstageRemotePrefixes,
        mergedPrefixes = uiState.mgstageMergedPrefixes,
        draft = uiState.newMgstagePrefix,
        numericDraft = uiState.newMgstageNumericPrefix,
        remoteConfigUrl = uiState.remoteScrapeConfigUrl,
        isRefreshing = uiState.isRefreshingMgstageRules,
        onDraftChange = onMgstagePrefixDraftChange,
        onNumericDraftChange = onMgstageNumericPrefixDraftChange,
        onRemoteConfigUrlChange = onRemoteScrapeConfigUrlChange,
        onAdd = onAddMgstagePrefix,
        onRemove = onRemoveMgstagePrefix,
        onRefresh = onRefreshMgstageRules
    )
    SettingsSectionTitle("番号识别")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenNumberRules)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "番号识别规则",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "忽略后缀 ${uiState.numberRecognitionIgnoredSuffixes.size} · 分段标记 ${uiState.numberRecognitionPartMarkers.size}",
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    SettingsSectionTitle("DMM2 跳过")
    Dmm2SkippedPrefixPanel(
        prefixes = uiState.dmm2SkippedPrefixes,
        draft = uiState.newDmm2SkippedPrefix,
        onDraftChange = onDmm2SkippedPrefixDraftChange,
        onAdd = onAddDmm2SkippedPrefix,
        onRemove = onRemoveDmm2SkippedPrefix
    )
    SettingsSectionTitle("刮削并发")
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
    SettingsSectionTitle("刮削代理")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("使用外部代理", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("仅用于刮削、图片下载和规则刷新", color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.useScrapeProxyEnabled, onCheckedChange = onScrapeProxyEnabledChange)
        }
        OutlinedTextField(
            value = uiState.scrapeProxyAddress,
            onValueChange = onScrapeProxyAddressChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.useScrapeProxyEnabled,
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            label = { Text("代理地址") },
            supportingText = { Text("例如 192.168.1.10:7890、http://192.168.1.10:7890 或 socks5://192.168.1.10:1080。保存后生效。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = settingsTextFieldColors()
        )
    }
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
}

@Composable
private fun NumberRecognitionRulesPage(
    uiState: SettingsUiState,
    onRefreshRules: () -> Unit
) {
    SettingsSectionTitle("热更新")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "GitHub 规则地址",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiState.remoteScrapeConfigUrl,
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Button(
            onClick = onRefreshRules,
            enabled = !uiState.isRefreshingMgstageRules,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            if (uiState.isRefreshingMgstageRules) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.Black)
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
            Text(
                text = if (uiState.isRefreshingMgstageRules) "正在刷新" else "刷新 GitHub 规则",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    SettingsSectionTitle("当前规则")
    NumberRecognitionRuleCard(
        title = "忽略后缀",
        subtitle = "例如 HHB，识别番号时会先剥离这些尾部噪声。",
        values = uiState.numberRecognitionIgnoredSuffixes
    )
    NumberRecognitionRuleCard(
        title = "分段标记",
        subtitle = "例如 RESTORED 会识别为 RESTORED 版本，RESTORED-A 会识别为 RESTORED-A。",
        values = uiState.numberRecognitionPartMarkers
    )
    NumberRecognitionRuleCard(
        title = "连写字母分段前缀",
        subtitle = "这些前缀后面直接跟 A/B 时会当作分段，不会并入番号。",
        values = uiState.numberRecognitionAttachedLetterPrefixes
    )
}

@Composable
private fun NumberRecognitionRuleCard(
    title: String,
    subtitle: String,
    values: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = if (values.isEmpty()) "暂无规则" else values.joinToString("、"),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun ScrapeTasksPage(
    uiState: SettingsUiState,
    onStartManualScrapeTasks: () -> Unit,
    onStopManualScrapeTasks: () -> Unit,
    onCancelManualScrapeTasks: () -> Unit,
    onRefreshScrapeTasks: () -> Unit,
    onResetFailedScrapeTasks: () -> Unit,
    onStartCloudFolderBatchTasks: () -> Unit,
    onStopCloudFolderBatchTasks: () -> Unit,
    onCancelCloudFolderBatchTasks: () -> Unit,
    onRefreshCloudFolderBatchTasks: () -> Unit
) {
    SettingsSectionTitle("影片刮削任务")
    ManualScrapeTaskPanel(
        uiState = uiState,
        onStart = onStartManualScrapeTasks,
        onStop = onStopManualScrapeTasks,
        onCancel = onCancelManualScrapeTasks,
        onRefresh = onRefreshScrapeTasks,
        onResetFailed = onResetFailedScrapeTasks
    )
    SettingsSectionTitle("网盘文件夹任务")
    CloudFolderBatchTaskPanel(
        uiState = uiState,
        onStart = onStartCloudFolderBatchTasks,
        onStop = onStopCloudFolderBatchTasks,
        onCancel = onCancelCloudFolderBatchTasks,
        onRefresh = onRefreshCloudFolderBatchTasks
    )
}

@Composable
private fun CustomJsonScrapeEntryCard(
    config: CustomJsonScrapeConfig,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = config.name.ifBlank { "自定义 JSON 来源" },
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (config.enabled) "已启用" else "未启用",
                color = Color.White.copy(alpha = if (config.enabled) 0.9f else 0.46f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = config.urlTemplate.ifBlank { "未配置请求地址" },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CustomJsonScrapePage(
    uiState: SettingsUiState,
    onConfigChange: (CustomJsonScrapeConfig) -> Unit,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
    onTestConfig: () -> Unit
) {
    SettingsSectionTitle("自定义 JSON 刮削")
    CustomJsonScrapePanel(
        config = uiState.customJsonScrapeConfig,
        configs = uiState.customJsonScrapeConfigs,
        selectedConfigIndex = uiState.selectedCustomJsonScrapeConfigIndex,
        isTesting = uiState.isTestingCustomJsonScrape,
        pathCandidates = uiState.customJsonPathCandidates,
        onChange = onConfigChange,
        onSelectConfig = onSelectConfig,
        onAddConfig = onAddConfig,
        onDuplicateConfig = onDuplicateConfig,
        onDeleteConfig = onDeleteConfig,
        onTest = onTestConfig
    )
}

@Composable
private fun CustomJsonScrapePanel(
    config: CustomJsonScrapeConfig,
    configs: List<CustomJsonScrapeConfig>,
    selectedConfigIndex: Int,
    isTesting: Boolean,
    pathCandidates: List<CustomJsonPathCandidate>,
    onChange: (CustomJsonScrapeConfig) -> Unit,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自定义 JSON 来源",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "先填 URL 和结果路径，点测试后可从样本 JSON 中选择字段。候选只来自当前结果路径。",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) }
            )
        }
        CustomJsonConfigSelector(
            configs = configs,
            selectedConfigIndex = selectedConfigIndex,
            onSelectConfig = onSelectConfig,
            onAddConfig = onAddConfig,
            onDuplicateConfig = onDuplicateConfig,
            onDeleteConfig = onDeleteConfig,
        )
        CustomJsonTextField("来源名称", config.name) { onChange(config.copy(name = it)) }
        CustomJsonTextField("请求地址", config.urlTemplate, "例如：https://example.com/api/movie?q={number}") {
            onChange(config.copy(urlTemplate = it))
        }
        CustomJsonTextField("示例番号", config.sampleNumber) { onChange(config.copy(sampleNumber = it)) }
        CustomJsonTextField("结果路径", config.resultPath, "例如：$.data 或 $.results[0]") {
            onChange(config.copy(resultPath = it))
        }
        CustomJsonMappingSummary(config = config, pathCandidates = pathCandidates)
        if (pathCandidates.isNotEmpty()) {
            Text(
                text = "已生成 ${pathCandidates.size} 个候选路径。需要调整字段时请重新点击测试并配置。",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onTest,
            enabled = !isTesting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Text(if (isTesting) "正在测试..." else "测试并生成字段候选")
        }
    }
}

@Composable
private fun CustomJsonConfigSelector(
    configs: List<CustomJsonScrapeConfig>,
    selectedConfigIndex: Int,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedConfig = configs.getOrNull(selectedConfigIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("配置方案", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(selectedConfig?.name?.ifBlank { "未命名配置" } ?: "未命名配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
                containerColor = Color(0xFF17202B),
            ) {
                configs.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.name.ifBlank { "未命名配置" }, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    item.urlTemplate.ifBlank { "未配置请求地址" },
                                    color = Color.White.copy(alpha = 0.62f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectConfig(index)
                        }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAddConfig, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Text("新增")
            }
            OutlinedButton(onClick = onDuplicateConfig, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Text("复制")
            }
            OutlinedButton(onClick = onDeleteConfig, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun CustomJsonMappingSummary(
    config: CustomJsonScrapeConfig,
    pathCandidates: List<CustomJsonPathCandidate>,
) {
    val targets = customJsonMappingTargets(config)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("字段映射", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        targets.chunked(2).forEach { rowTargets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowTargets.forEach { target ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                    ) {
                        Text(target.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            target.value.ifBlank { "未选择" },
                            color = if (target.value.isBlank()) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sampleValue = pathCandidates.sampleValue(target.value)
                        Text(
                            sampleValue.ifBlank { if (target.value.isBlank()) "未映射" else "样本未匹配" },
                            color = if (sampleValue.isBlank()) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowTargets.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CustomJsonTextField(
    label: String,
    value: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        colors = settingsTextFieldColors()
    )
}

@Composable
private fun MgstagePrefixPanel(
    customPrefixes: Map<String, String>,
    remotePrefixes: Map<String, String>,
    mergedPrefixes: Map<String, String>,
    draft: String,
    numericDraft: String,
    remoteConfigUrl: String,
    isRefreshing: Boolean,
    onDraftChange: (String) -> Unit,
    onNumericDraftChange: (String) -> Unit,
    onRemoteConfigUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit
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
                    text = "番号前缀规则",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (mergedPrefixes.isEmpty()) {
                        "未设置 MGStage 前缀。"
                    } else {
                        "本地 ${customPrefixes.size} · GitHub ${remotePrefixes.size} · 合并 ${mergedPrefixes.size} 个：${mergedPrefixes.entries.joinToString("、") { it.toMgstageRuleLabel() }}"
                    },
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (isRefreshing) "刷新中" else "刷新")
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("管理")
            }
        }
        Text(
            text = "命中合并规则后，默认刮削会优先尝试 MGStage。",
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("MGStage 番号规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本地自定义前缀会和 GitHub 热更新规则合并。")
                    OutlinedTextField(
                        value = remoteConfigUrl,
                        onValueChange = onRemoteConfigUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("GitHub 规则地址") }
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text(
                            text = if (isRefreshing) "正在刷新 GitHub 规则" else "刷新 GitHub 规则",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("标准番号前缀") },
                        placeholder = { Text("例如 MIUM、SCUTE") }
                    )
                    OutlinedTextField(
                        value = numericDraft,
                        onValueChange = onNumericDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("MGStage 搜索时附加的数字") },
                        placeholder = { Text("例如 MIUM 填 300，SCUTE 填 229") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("添加映射")
                    }
                    MgstagePrefixList(
                        title = "本地自定义",
                        prefixes = customPrefixes,
                        emptyText = "暂无本地前缀",
                        onRemove = onRemove
                    )
                    MgstagePrefixList(
                        title = "GitHub 缓存",
                        prefixes = remotePrefixes,
                        emptyText = "暂无 GitHub 缓存",
                        onRemove = null
                    )
                    Text(
                        text = "最终合并 ${mergedPrefixes.size} 个前缀。",
                        style = MaterialTheme.typography.bodySmall
                    )
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
private fun MgstagePrefixList(
    title: String,
    prefixes: Map<String, String>,
    emptyText: String,
    onRemove: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (prefixes.isEmpty()) {
                Text(emptyText)
            } else {
                prefixes.forEach { (prefix, numericPrefix) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$prefix → ${numericPrefix.ifBlank { "无需附加" }}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onRemove != null) {
                            TextButton(onClick = { onRemove(prefix) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Map.Entry<String, String>.toMgstageRuleLabel(): String =
    "$key→${value.ifBlank { "无" }}"

@Composable
private fun ManualScrapeTaskPanel(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onResetFailed: () -> Unit
) {
    val summary = uiState.scrapeTaskSummary
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
                    text = "刮削任务",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "待刮削 ${summary.pending} · 运行中 ${summary.running} · 失败 ${summary.failed} · 已完成 ${summary.completed}",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, enabled = !uiState.isManualScrapeRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新刮削任务", tint = Color.White)
            }
        }
        if (uiState.isManualScrapeRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = uiState.scrapeTaskMessage.ifBlank {
                if (summary.unfinished > 0) {
                    "请挂节点后手动启动刮削。重新进入 App 不会自动继续未完成任务。"
                } else {
                    "暂无未完成的刮削任务。"
                }
            },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = if (uiState.isManualScrapeRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            Icon(
                imageVector = if (uiState.isManualScrapeRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null
            )
            Text(
                text = if (uiState.isManualScrapeRunning) "暂停刮削任务" else "开始/继续刮削任务",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onCancel,
            enabled = summary.unfinished > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskCancelButtonColors()
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text(
                text = "取消任务",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onResetFailed,
            enabled = summary.failed > 0 && !uiState.isManualScrapeRunning,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = manualTaskSecondaryButtonColors()
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text(
                text = "重置失败任务为待刮削",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CloudFolderBatchTaskPanel(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit
) {
    val tasks = uiState.cloudFolderBatchTasks
    val pending = tasks.count { it.status == CloudFolderBatchTaskStatus.Pending.name }
    val running = tasks.count { it.status == CloudFolderBatchTaskStatus.Running.name }
    val paused = tasks.count { it.status == CloudFolderBatchTaskStatus.Paused.name }
    val failed = tasks.count { it.status == CloudFolderBatchTaskStatus.Failed.name }
    val completed = tasks.count { it.status == CloudFolderBatchTaskStatus.Completed.name }
    val unfinished = pending + running + paused + failed
    val activeTasks = tasks.filter { it.status != CloudFolderBatchTaskStatus.Completed.name }
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
                    text = "网盘文件夹任务",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "待执行 $pending · 运行中 $running · 已暂停 $paused · 失败 $failed · 已完成 $completed",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, enabled = !uiState.isCloudFolderBatchRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新网盘文件夹任务", tint = Color.White)
            }
        }
        if (uiState.isCloudFolderBatchRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = uiState.cloudFolderBatchTaskMessage.ifBlank {
                if (unfinished > 0) {
                    "文件夹添加任务已记录到数据库。重新进入 App 不会自动继续，需要在这里手动启动。"
                } else {
                    "暂无未完成的网盘文件夹任务。"
                }
            },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = if (uiState.isCloudFolderBatchRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            Icon(
                imageVector = if (uiState.isCloudFolderBatchRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null
            )
            Text(
                text = if (uiState.isCloudFolderBatchRunning) "暂停文件夹任务" else "开始/继续文件夹任务",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onCancel,
            enabled = unfinished > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskCancelButtonColors()
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text(
                text = "取消任务",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activeTasks.isEmpty()) {
                Text(
                    text = "暂无当前文件夹任务。",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                activeTasks.forEach { task ->
                    CloudFolderBatchTaskRow(
                        task = task,
                        runnerRunning = uiState.isCloudFolderBatchRunning
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudFolderBatchTaskRow(
    task: CloudFolderBatchTaskEntity,
    runnerRunning: Boolean
) {
    val progress = if (task.queuedVideos > 0) {
        task.processedVideos.coerceIn(0, task.queuedVideos).toFloat() / task.queuedVideos.toFloat()
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.folderName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.statusLabel(runnerRunning),
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (task.queuedVideos > 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = if (task.queuedVideos > 0) {
                "进度 ${task.processedVideos.coerceAtMost(task.queuedVideos)}/${task.queuedVideos}"
            } else {
                "正在收集候选视频"
            },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val currentText = listOfNotNull(
            task.currentPath?.takeIf { it.isNotBlank() },
            task.currentFileName?.takeIf { it.isNotBlank() }
        ).joinToString(" / ")
        if (currentText.isNotBlank()) {
            Text(
                text = "当前：$currentText",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "入库 ${task.addedVideos} · 跳过 ${task.skippedVideos} · 刮削失败 ${task.scrapeFailedVideos} · 失败 ${task.failedVideos + task.failedFolders}",
            color = Color.White.copy(alpha = 0.54f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        task.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.50f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
    }
}

private fun CloudFolderBatchTaskEntity.statusLabel(runnerRunning: Boolean): String =
    when (status) {
        CloudFolderBatchTaskStatus.Pending.name -> "待执行"
        CloudFolderBatchTaskStatus.Running.name -> if (runnerRunning) "运行中" else "中断待继续"
        CloudFolderBatchTaskStatus.Paused.name -> "已暂停"
        CloudFolderBatchTaskStatus.Completed.name -> "已完成"
        CloudFolderBatchTaskStatus.Failed.name -> "失败"
        else -> status
    }

@Composable
private fun manualTaskSecondaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.White.copy(alpha = 0.16f),
    contentColor = Color.White,
    disabledContainerColor = Color.White.copy(alpha = 0.08f),
    disabledContentColor = Color.White.copy(alpha = 0.42f)
)

@Composable
private fun taskPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.White,
    contentColor = Color.Black,
    disabledContainerColor = Color.White.copy(alpha = 0.55f),
    disabledContentColor = Color.Black.copy(alpha = 0.62f)
)

@Composable
private fun taskCancelButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF7B2E2E),
    contentColor = Color.White,
    disabledContainerColor = Color.White.copy(alpha = 0.10f),
    disabledContentColor = Color.White.copy(alpha = 0.42f)
)

@Composable
private fun PriorityScrapeSourcePanel(
    sources: List<ScrapeSource>,
    options: List<ScrapeSource>,
    onAdd: (ScrapeSource) -> Unit,
    onRemove: (ScrapeSource) -> Unit,
    onMoveUp: (ScrapeSource) -> Unit,
    onMoveDown: (ScrapeSource) -> Unit
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
                    text = "优先级刮削顺序",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sources.joinToString(" -> ") { it.label },
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
        Text(
            text = "默认刮削会按这里的顺序依次尝试，成功一个就停止。手动指定 TheJavDB、JavBus 等来源时不受这个顺序影响。",
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "使用 TheJavDB 接口刮削，日本节点仍然可用，下载图片需要日本节点。",
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showManageDialog) {
        var addMenuExpanded by remember { mutableStateOf(false) }
        val available = options.filter { it !in sources }
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("优先级刮削顺序") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("例如 DMM2 -> TheJavDB -> JavBus，前一个失败后会自动尝试下一个。")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sources.forEachIndexed { index, source ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}. ${source.label}",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(
                                    enabled = index > 0,
                                    onClick = { onMoveUp(source) }
                                ) {
                                    Text("上移")
                                }
                                TextButton(
                                    enabled = index < sources.lastIndex,
                                    onClick = { onMoveDown(source) }
                                ) {
                                    Text("下移")
                                }
                                TextButton(
                                    enabled = sources.size > 1,
                                    onClick = { onRemove(source) }
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { addMenuExpanded = true },
                            enabled = available.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = if (available.isEmpty()) "所有可用来源都已加入" else "添加来源",
                                modifier = Modifier.weight(1f)
                            )
                            Text("选择")
                        }
                        DropdownMenu(
                            expanded = addMenuExpanded,
                            onDismissRequest = { addMenuExpanded = false }
                        ) {
                            available.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.label) },
                                    onClick = {
                                        addMenuExpanded = false
                                        onAdd(source)
                                    }
                                )
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
        ScrapeSource.Priority -> "优先级刮削"
        ScrapeSource.Dmm -> "DMM"
        ScrapeSource.Dmm2 -> "DMM2"
        ScrapeSource.Official -> "Official"
        ScrapeSource.Mgstage -> "MGStage"
        ScrapeSource.Javbus -> "JavBus"
        ScrapeSource.TheJavDB -> "TheJavDB"
        ScrapeSource.CustomJson -> "自定义 JSON"
    }
