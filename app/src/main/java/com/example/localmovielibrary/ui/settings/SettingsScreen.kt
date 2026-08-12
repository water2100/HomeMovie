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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.local.ScrapeTaskStatus
import com.example.localmovielibrary.data.local.CloudVideoTaskEntity
import com.example.localmovielibrary.data.local.CloudVideoTaskStatus
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.DailyUsageStats
import com.example.localmovielibrary.data.repository.UsageStatsRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrary.scraper.CustomJsonPathCandidate
import com.example.localmovielibrary.scraper.NumberPrefixRewriteRule
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.ScrapedMovieInfo
import com.example.localmovielibrary.ui.shared.MovieImageCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SettingsBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background

private enum class SettingsPage {
    Directory,
    Cloud,
    Scrape,
    CustomJsonScrape,
    NumberRecognition,
    ScrapeTasks,
    Player,
    PlayerSubtitle,
    PlayerProgressBar,
    StartupAnimation,
    Update,
    UsageStats
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
    var usageStats by remember { mutableStateOf(emptyList<DailyUsageStats>()) }
    val contentScrollState = when (currentPage) {
        null -> overviewScrollState
        SettingsPage.Update -> updateScrollState
        else -> pageScrollState
    }

    BackHandler(enabled = currentPage != null) {
        if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
            onBack()
        } else {
            currentPage = if (currentPage == SettingsPage.PlayerSubtitle || currentPage == SettingsPage.PlayerProgressBar) SettingsPage.Player else null
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
        when (currentPage) {
            SettingsPage.Cloud -> viewModel.refreshSavedCloud115Accounts()
            SettingsPage.ScrapeTasks -> viewModel.refreshScrapeTaskSummary()
            SettingsPage.UsageStats -> usageStats = UsageStatsRepository(context).recent()
            else -> Unit
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = currentPage?.titleText() ?: "设置",
                onBack = currentPage?.let {
                    if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
                        onBack
                    } else {
                        { currentPage = if (currentPage == SettingsPage.PlayerSubtitle || currentPage == SettingsPage.PlayerProgressBar) SettingsPage.Player else null }
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
                        onSave = viewModel::save,
                        onLightThemeChange = viewModel::updateLightThemeEnabled
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
                        onImageRetryCountChange = viewModel::updateImageDownloadRetryCount,
                        onScrapeRetryCountChange = viewModel::updateScrapeRetryCount,
                        onConcurrencyLimitChange = viewModel::updateScrapeConcurrencyLimit,
                        onScrapeProxyAddressChange = viewModel::updateScrapeProxyAddress,
                        onScrapeProxyEnabledChange = viewModel::updateScrapeProxyEnabled,
                        onDmm2SkippedPrefixDraftChange = viewModel::updateNewDmm2SkippedPrefix,
                        onAddDmm2SkippedPrefix = viewModel::addDmm2SkippedPrefix,
                        onRemoveDmm2SkippedPrefix = viewModel::removeDmm2SkippedPrefix,
                        onNumberPrefixRulePrefixDraftChange = viewModel::updateNewNumberPrefixRulePrefix,
                        onNumberPrefixRuleNumericDraftChange = viewModel::updateNewNumberPrefixRuleNumericPrefix,
                        onNumberPrefixRuleSourcesChange = viewModel::updateNewNumberPrefixRuleSources,
                        onAddNumberPrefixRule = viewModel::addNumberPrefixRewriteRule,
                        onRemoveNumberPrefixRule = viewModel::removeNumberPrefixRewriteRule,
                        onMgstageAmateurPriorityChange = viewModel::updateMgstageAmateurPriorityEnabled,
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
                        onRefreshCloudFolderBatchTasks = viewModel::refreshCloudFolderBatchTasks,
                        onClearCompletedCloudVideoTasks = viewModel::clearCompletedCloudVideoTasks
                    )

                    SettingsPage.Player -> PlayerSettingsPage(
                        uiState = uiState,
                        onSeekBackSecondsChange = viewModel::updatePlayerSeekBackSeconds,
                        onSeekForwardSecondsChange = viewModel::updatePlayerSeekForwardSeconds,
                        onOpenSubtitle = { currentPage = SettingsPage.PlayerSubtitle },
                        onOpenProgressBar = { currentPage = SettingsPage.PlayerProgressBar }
                    )

                    SettingsPage.PlayerSubtitle -> ExternalSubtitleSettingsPage(
                        uiState = uiState,
                        onExternalSubtitleFontSizeChange = viewModel::updateExternalSubtitleFontSizeSp,
                        onExternalSubtitleBottomPaddingChange = viewModel::updateExternalSubtitleBottomPaddingPercent,
                        onExternalSubtitleBackgroundAlphaChange = viewModel::updateExternalSubtitleBackgroundAlphaPercent
                    )

                    SettingsPage.PlayerProgressBar -> PlayerProgressBarSettingsPage(
                        uiState = uiState,
                        onWidthChange = viewModel::updatePlayerProgressBarWidthDp,
                        onColorChange = viewModel::updatePlayerProgressBarColor,
                        onAlphaChange = viewModel::updatePlayerProgressBarAlphaPercent
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

                    SettingsPage.UsageStats -> UsageStatsPage(UsageStatsRepository(context))
                }
                if (currentPage != null && currentPage != SettingsPage.ScrapeTasks) {
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
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
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
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
                                color = if (selectedTarget?.value.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!selectedSampleValue.isNullOrBlank()) {
                                Text(
                                    text = "样本值：$selectedSampleValue",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                targets.forEachIndexed { index, target ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(target.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                                Text(
                                                    target.value.ifBlank { "未选择" },
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
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
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
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
                            Text(node.path, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (node.valuePreview.isNotBlank()) {
                                Text(
                                    node.valuePreview,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("当前结果路径：${config.resultPath}", style = MaterialTheme.typography.bodySmall)
        Text("提示：如果想选评分 $.reviewSummary.average，请把结果路径设到包含 reviewSummary 的节点后重新测试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
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
    SettingsPage.PlayerSubtitle -> "外挂字幕"
    SettingsPage.PlayerProgressBar -> "进度条"
    SettingsPage.StartupAnimation -> "开场动画"
    SettingsPage.Update -> "应用更新"
    SettingsPage.UsageStats -> "使用统计"
}

@Composable
private fun SettingsOverviewPage(
    uiState: SettingsUiState,
    imageCacheSizeText: String,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenLogs: () -> Unit,
    onSave: () -> Unit,
    onLightThemeChange: (Boolean) -> Unit
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
            subtitle = "${uiState.defaultScrapeSource.label} · 并发 ${uiState.scrapeConcurrencyLimitText} · 刮削重试 ${uiState.scrapeRetryCountText} 次 · 图片重试 ${uiState.imageDownloadRetryCountText} 次 · 缓存 $imageCacheSizeText",
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("浅色主题", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("切换后界面背景与文字颜色会自动适配", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.lightThemeEnabled, onCheckedChange = onLightThemeChange)
        }
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
        SettingsEntryRow(
            title = "使用统计",
            subtitle = "每天的使用时长与播放时长",
            onClick = { onOpenPage(SettingsPage.UsageStats) }
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
                Text("启用开场动画", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("进入应用时显示图片和加载动画，默认关闭。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.startupAnimationEnabled, onCheckedChange = onEnabledChange)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("动画图片", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (uiState.startupAnimationImageUri.isBlank()) {
                Text("未选择时将显示内置深色渐变画面。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
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
    onOpenSubtitle: () -> Unit,
    onOpenProgressBar: () -> Unit
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
    SettingsSectionTitle("播放器控件")
    SettingsGroupCard(title = "样式与预览") {
        SettingsEntryRow(
            title = "外挂字幕",
            subtitle = "字号 ${uiState.externalSubtitleFontSizeSp}sp · 点击进入实时预览",
            onClick = onOpenSubtitle
        )
        SettingsEntryRow(
            title = "进度条",
            subtitle = "宽度 ${uiState.playerProgressBarWidthDp}dp · 点击进入实时预览",
            onClick = onOpenProgressBar
        )
    }
}

@Composable
private fun UsageStatsPage(repository: UsageStatsRepository) {
    var period by rememberSaveable { mutableStateOf(UsageStatsPeriod.Week) }
    val allStats = repository.all()
    val stats = when (period) {
        UsageStatsPeriod.Week -> repository.recent(7)
        UsageStatsPeriod.Month -> repository.recent(30)
        UsageStatsPeriod.All -> allStats.takeLast(30)
    }
    val maxMillis = stats.maxOfOrNull { maxOf(it.appMillis, it.playbackMillis) }?.coerceAtLeast(1L) ?: 1L
    val totalSource = if (period == UsageStatsPeriod.All) allStats else stats
    val totalApp = totalSource.sumOf { it.appMillis }
    val totalPlayback = totalSource.sumOf { it.playbackMillis }
    SettingsSectionTitle("使用统计")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageStatsPeriod.entries.forEach { option ->
            OutlinedButton(onClick = { period = option }) { Text(option.label) }
        }
    }
    SettingsGroupCard(title = "使用与播放时长") {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            stats.forEach { item ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.heightIn(min = 120.dp), verticalAlignment = Alignment.Bottom) {
                        Box(modifier = Modifier.width(10.dp).height((120f * item.appMillis / maxMillis).dp.coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                        Spacer(Modifier.width(3.dp))
                        Box(modifier = Modifier.width(10.dp).height((120f * item.playbackMillis / maxMillis).dp.coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                    }
                    if (period != UsageStatsPeriod.Month || stats.indexOf(item) % 5 == 0) Text(item.day, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Text("蓝色：使用软件 · 紫色：实际播放", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
    }
    SettingsGroupCard(title = "汇总") {
        Text("${period.label}使用 ${formatUsageDuration(totalApp)} · 播放 ${formatUsageDuration(totalPlayback)}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
    if (period == UsageStatsPeriod.Week) SettingsGroupCard(title = "本周每日记录") {
        stats.forEach { item -> Text("${item.day}　使用 ${formatUsageDuration(item.appMillis)}　播放 ${formatUsageDuration(item.playbackMillis)}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) }
    }
}

private enum class UsageStatsPeriod(val label: String) { Week("7天"), Month("一个月"), All("总时间") }

private fun formatUsageDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分" else "${minutes}分"
}

@Composable
private fun ExternalSubtitleSettingsPage(
    uiState: SettingsUiState,
    onExternalSubtitleFontSizeChange: (Int) -> Unit,
    onExternalSubtitleBottomPaddingChange: (Int) -> Unit,
    onExternalSubtitleBackgroundAlphaChange: (Int) -> Unit
) {
    SettingsSectionTitle("实时预览")
    SubtitleStylePreview(uiState)
    SettingsSectionTitle("字幕显示")
    SettingsGroupCard(title = "外挂字幕样式") {
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
private fun PlayerProgressBarSettingsPage(
    uiState: SettingsUiState,
    onWidthChange: (Int) -> Unit,
    onColorChange: (Int) -> Unit,
    onAlphaChange: (Int) -> Unit
) {
    val progressColor = Color(uiState.playerProgressBarColor).copy(alpha = uiState.playerProgressBarAlphaPercent / 100f)
    SettingsSectionTitle("实时预览")
    SettingsGroupCard(title = "播放进度") {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text("00:42", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(99.dp))
                    .heightIn(min = uiState.playerProgressBarWidthDp.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.46f)
                        .heightIn(min = uiState.playerProgressBarWidthDp.dp)
                        .background(progressColor, RoundedCornerShape(99.dp))
                )
            }
            Text("01:30", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End))
        }
    }
    SettingsSectionTitle("样式")
    SettingsGroupCard(title = "进度条") {
        SubtitleStyleSliderRow("宽度", uiState.playerProgressBarWidthDp, AppSettingsRepository.MIN_PLAYER_PROGRESS_BAR_WIDTH_DP..AppSettingsRepository.MAX_PLAYER_PROGRESS_BAR_WIDTH_DP, "${uiState.playerProgressBarWidthDp}dp", onWidthChange)
        SubtitleStyleSliderRow("不透明度", uiState.playerProgressBarAlphaPercent, AppSettingsRepository.MIN_PLAYER_PROGRESS_BAR_ALPHA_PERCENT..AppSettingsRepository.MAX_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, "${uiState.playerProgressBarAlphaPercent}%", onAlphaChange)
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0xFFFFFFFF.toInt(), 0xFFFF5252.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt()).forEach { color ->
                Box(modifier = Modifier.size(32.dp).background(Color(color), CircleShape).clickable { onColorChange(color) })
            }
        }
    }
}

@Composable
private fun SubtitleStylePreview(uiState: SettingsUiState) {
    val background = Color.Black.copy(alpha = uiState.externalSubtitleBackgroundAlphaPercent / 100f)
    SettingsGroupCard(title = "播放效果") {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp).heightIn(min = 180.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = "这是一段外挂字幕预览",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = uiState.externalSubtitleFontSizeSp.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp).padding(bottom = uiState.externalSubtitleBottomPaddingPercent.dp)
            )
        }
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
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { next -> onValueChange(next.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "家庭电影院 ${uiState.appVersionName.ifBlank { "未知" }}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "versionCode ${uiState.appVersionCode}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "APK 缓存位置",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiState.updateApkDirectoryPath.ifBlank { "暂未创建缓存目录" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = uiState.downloadedUpdateApkPath.ifBlank { "当前没有已下载的更新 APK" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = update?.let { "最新版本 ${it.versionName} · versionCode ${it.versionCode}" } ?: "尚未检查更新",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        update?.sizeBytes?.let { size ->
            Text(
                text = "APK 大小：${formatCacheSize(size)}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (update != null && update.notes.isNotEmpty()) {
            Text(
                text = update.notes.take(5).joinToString("\n") { "- $it" },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.updateMessage.isNotBlank()) {
            Text(
                text = uiState.updateMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.isDownloadingUpdate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "${uiState.updateDownloadProgress.coerceIn(0, 100)}%",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "生成 .nomedia 文件屏蔽图片",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "开启后会在影片库目录创建 .nomedia，避免相册显示海报和剧照；关闭后删除该文件。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
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
        shape = RoundedCornerShape(20.dp),
        colors = libraryScanButtonPalette().let { palette ->
            ButtonDefaults.buttonColors(
                containerColor = palette.containerColor,
                contentColor = palette.contentColor,
                disabledContainerColor = palette.disabledContainerColor,
                disabledContentColor = palette.disabledContentColor
            )
        }
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
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "开启国产页面",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "关闭后，影片页面不显示国产分类。默认关闭。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "关闭后，网盘点击添加不会弹出正在添加、已添加这类提示。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "开启后，影片详情删除弹窗会显示“删除(网盘)”，同时删除 115 网盘真实视频。默认关闭。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已排除 ${names.size} 个视频。命中后仍可播放，但不显示添加按钮。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
    onImageRetryCountChange: (String) -> Unit,
    onScrapeRetryCountChange: (String) -> Unit,
    onConcurrencyLimitChange: (String) -> Unit,
    onScrapeProxyAddressChange: (String) -> Unit,
    onScrapeProxyEnabledChange: (Boolean) -> Unit,
    onDmm2SkippedPrefixDraftChange: (String) -> Unit,
    onAddDmm2SkippedPrefix: () -> Unit,
    onRemoveDmm2SkippedPrefix: (String) -> Unit,
    onNumberPrefixRulePrefixDraftChange: (String) -> Unit,
    onNumberPrefixRuleNumericDraftChange: (String) -> Unit,
    onNumberPrefixRuleSourcesChange: (Set<ScrapeSource>) -> Unit,
    onAddNumberPrefixRule: () -> Unit,
    onRemoveNumberPrefixRule: (String) -> Unit,
    onMgstageAmateurPriorityChange: (Boolean) -> Unit,
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
        amateurPriorityEnabled = uiState.mgstageAmateurPriorityEnabled,
        onDraftChange = onMgstagePrefixDraftChange,
        onNumericDraftChange = onMgstageNumericPrefixDraftChange,
        onRemoteConfigUrlChange = onRemoteScrapeConfigUrlChange,
        onAdd = onAddMgstagePrefix,
        onRemove = onRemoveMgstagePrefix,
        onRefresh = onRefreshMgstageRules,
        onAmateurPriorityChange = onMgstageAmateurPriorityChange
    )
    SettingsSectionTitle("数字前缀规则")
    NumberPrefixRewriteRulePanel(
        rules = uiState.numberPrefixRewriteRules,
        prefixDraft = uiState.newNumberPrefixRulePrefix,
        numericPrefixDraft = uiState.newNumberPrefixRuleNumericPrefix,
        selectedSources = uiState.newNumberPrefixRuleSources,
        onPrefixDraftChange = onNumberPrefixRulePrefixDraftChange,
        onNumericPrefixDraftChange = onNumberPrefixRuleNumericDraftChange,
        onSourcesChange = onNumberPrefixRuleSourcesChange,
        onAdd = onAddNumberPrefixRule,
        onRemove = onRemoveNumberPrefixRule
    )
    SettingsSectionTitle("番号识别")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenNumberRules)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "番号识别规则",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "忽略后缀 ${uiState.numberRecognitionIgnoredSuffixes.size} · 分段标记 ${uiState.numberRecognitionPartMarkers.size}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
    SettingsSectionTitle("刮削重试")
    OutlinedTextField(
        value = uiState.scrapeRetryCountText,
        onValueChange = onScrapeRetryCountChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        label = { Text("每个来源尝试次数") },
        supportingText = { Text("范围 1 到 ${AppSettingsRepository.MAX_SCRAPE_RETRY_COUNT}，默认 ${AppSettingsRepository.DEFAULT_SCRAPE_RETRY_COUNT} 次；达到上限后再尝试下一个优先级来源。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    SettingsSectionTitle("刮削代理")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("使用外部代理", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("仅用于刮削、图片下载和规则刷新", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
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
            supportingText = { Text("例如 192.168.1.10:7890、http://192.168.1.10:7890 或 socks5://192.168.1.10:1080。开关即时生效，地址需保存后生效。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = settingsTextFieldColors()
        )
    }
    SettingsSectionTitle("图片下载")
    OutlinedTextField(
        value = uiState.imageDownloadRetryCountText,
        onValueChange = onImageRetryCountChange,
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "GitHub 规则地址",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiState.remoteScrapeConfigUrl,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = if (values.isEmpty()) "暂无规则" else values.joinToString("、"),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
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
    onRefreshCloudFolderBatchTasks: () -> Unit,
    onClearCompletedCloudVideoTasks: () -> Unit
) {
    SettingsSectionTitle("统一刮削任务")
    UnifiedScrapeTaskPanel(
        uiState = uiState,
        onStart = {
            onStartManualScrapeTasks()
            onStartCloudFolderBatchTasks()
        },
        onStop = {
            onStopManualScrapeTasks()
            onStopCloudFolderBatchTasks()
        },
        onCancel = {
            onCancelManualScrapeTasks()
            onCancelCloudFolderBatchTasks()
        },
        onRefresh = {
            onRefreshScrapeTasks()
            onRefreshCloudFolderBatchTasks()
        },
        onResetFailed = onResetFailedScrapeTasks
    )
    SettingsSectionTitle("影片任务（${uiState.scrapeIssueMovies.size}）")
    ScrapeIssueList(movies = uiState.scrapeIssueMovies, isRunning = uiState.isManualScrapeRunning)
    SettingsSectionTitle("网盘视频任务（${uiState.cloudVideoTasks.size}）")
    if (uiState.cloudVideoTasks.any { it.status == CloudVideoTaskStatus.Completed.name }) {
        OutlinedButton(
            onClick = onClearCompletedCloudVideoTasks,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text("清除已完成记录", modifier = Modifier.padding(start = 8.dp))
        }
    }
    CloudVideoTaskList(tasks = uiState.cloudVideoTasks, isRunning = uiState.isCloudVideoTaskRunning)
    SettingsSectionTitle("文件夹发现任务（${uiState.cloudFolderBatchTasks.size}）")
    CloudFolderBatchTaskList(
        tasks = uiState.cloudFolderBatchTasks,
        isRunning = uiState.isCloudFolderBatchRunning
    )
}

@Composable
private fun UnifiedScrapeTaskPanel(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onResetFailed: () -> Unit
) {
    val movieSummary = uiState.scrapeTaskSummary
    val cloudVideoUnfinished = uiState.cloudVideoTasks.count { it.status != CloudVideoTaskStatus.Completed.name }
    val folderUnfinished = uiState.cloudFolderBatchTasks.count { it.status != CloudFolderBatchTaskStatus.Completed.name }
    val isRunning = uiState.isManualScrapeRunning || uiState.isCloudVideoTaskRunning || uiState.isCloudFolderBatchRunning
    val unfinished = movieSummary.unfinished + cloudVideoUnfinished + folderUnfinished
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("所有来源", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "影片 ${movieSummary.unfinished} · 网盘视频 $cloudVideoUnfinished · 文件夹 $folderUnfinished",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新刮削任务", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = if (isRunning) "正在按统一队列处理任务。" else "所有入口共用同一套并发、暂停和失败恢复机制。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            Icon(if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
            Text(if (isRunning) "暂停所有刮削任务" else "开始/继续所有刮削任务", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onCancel,
            enabled = unfinished > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskCancelButtonColors()
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text("取消所有未完成任务", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onResetFailed,
            enabled = movieSummary.failed > 0 && !isRunning,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = manualTaskSecondaryButtonColors()
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text("重置失败的影片任务", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CustomJsonScrapeEntryCard(
    config: CustomJsonScrapeConfig,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = config.name.ifBlank { "自定义 JSON 来源" },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (config.enabled) "已启用" else "未启用",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.enabled) 0.9f else 0.46f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = config.urlTemplate.ifBlank { "未配置请求地址" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "先填 URL 和结果路径，点测试后可从样本 JSON 中选择字段。候选只来自当前结果路径。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("配置方案", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                configs.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.name.ifBlank { "未命名配置" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(
                                    item.urlTemplate.ifBlank { "未配置请求地址" },
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("字段映射", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        targets.chunked(2).forEach { rowTargets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowTargets.forEach { target ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                    ) {
                        Text(target.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            target.value.ifBlank { "未选择" },
                            color = if (target.value.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sampleValue = pathCandidates.sampleValue(target.value)
                        Text(
                            sampleValue.ifBlank { if (target.value.isBlank()) "未映射" else "样本未匹配" },
                            color = if (sampleValue.isBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
    amateurPriorityEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onNumericDraftChange: (String) -> Unit,
    onRemoteConfigUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onAmateurPriorityChange: (Boolean) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (mergedPrefixes.isEmpty()) {
                        "未设置 MGStage 前缀。"
                    } else {
                        "本地 ${customPrefixes.size} · GitHub ${remotePrefixes.size} · 合并 ${mergedPrefixes.size} 个：${mergedPrefixes.entries.joinToString("、") { it.toMgstageRuleLabel() }}"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MGS素人优先", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "开启后，命中 MGS 前缀规则的影片会优先尝试 MGStage；关闭则严格按刮削源优先级执行。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = amateurPriorityEnabled, onCheckedChange = onAmateurPriorityChange)
        }
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
private fun NumberPrefixRewriteRulePanel(
    rules: List<NumberPrefixRewriteRule>,
    prefixDraft: String,
    numericPrefixDraft: String,
    selectedSources: Set<ScrapeSource>,
    onPrefixDraftChange: (String) -> Unit,
    onNumericPrefixDraftChange: (String) -> Unit,
    onSourcesChange: (Set<ScrapeSource>) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("按刮削源补全数字前缀", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = if (rules.isEmpty()) "未设置。命中后会用完整番号命名影片。" else "已设置 ${rules.size} 条：${rules.joinToString("、") { "${it.prefix}→${it.rewrittenPrefix}" }}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { showManageDialog = true }, shape = RoundedCornerShape(18.dp)) {
                Text("管理")
            }
        }
        Text(
            text = "例如 DSVR + 3：仅对勾选来源以 3DSVR-1944 查询；无论最终由哪个来源成功，目录、STRM 和 NFO 都使用 3DSVR-1944。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("数字前缀规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = prefixDraft,
                        onValueChange = onPrefixDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("标准番号前缀") },
                        placeholder = { Text("例如 DSVR") },
                        colors = settingsTextFieldColors()
                    )
                    OutlinedTextField(
                        value = numericPrefixDraft,
                        onValueChange = onNumericPrefixDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("附加数字") },
                        placeholder = { Text("例如 3") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = settingsTextFieldColors()
                    )
                    Text("应用刮削源", style = MaterialTheme.typography.titleSmall)
                    ScrapeSource.entries.filter { it != ScrapeSource.Priority }.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSourcesChange(
                                        if (source in selectedSources) selectedSources - source else selectedSources + source
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = source in selectedSources,
                                onCheckedChange = { checked ->
                                    onSourcesChange(if (checked) selectedSources + source else selectedSources - source)
                                }
                            )
                            Text(source.label)
                        }
                    }
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Text("添加或覆盖规则")
                    }
                    if (rules.isEmpty()) {
                        Text("暂无规则")
                    } else {
                        rules.forEach { rule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${rule.prefix} → ${rule.rewrittenPrefix}（${rule.sources.joinToString("、") { it.label }}）",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { onRemove(rule.prefix) }) { Text("删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManageDialog = false }) { Text("完成") } }
        )
    }
}

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
    val isRunning = uiState.isManualScrapeRunning || uiState.isCloudVideoTaskRunning
    val unfinishedVideoTasks = uiState.cloudVideoTasks.count { it.status != CloudVideoTaskStatus.Completed.name }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "持久化单视频 $unfinishedVideoTasks · 未刮削 ${summary.unscraped} · 待刮削 ${summary.pending} · 运行中 ${summary.running} · 失败 ${summary.failed}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新刮削任务", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (isRunning) {
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = taskPrimaryButtonColors()
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null
            )
            Text(
                text = if (isRunning) "暂停影片任务" else "开始持久化队列及批量刮削",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onCancel,
            enabled = summary.unfinished > 0 || unfinishedVideoTasks > 0,
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
            enabled = summary.failed > 0 && !isRunning,
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
private fun CloudVideoTaskList(
    tasks: List<CloudVideoTaskEntity>,
    isRunning: Boolean
) {
    if (tasks.isEmpty()) {
        Text(
            text = "暂无单视频添加任务。点击网盘影片的添加按钮后会立即持久化到这里。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = tasks, key = CloudVideoTaskEntity::pickcode) { task ->
            val status = runCatching { CloudVideoTaskStatus.valueOf(task.status) }
                .getOrDefault(CloudVideoTaskStatus.Pending)
            val statusText = when (status) {
                CloudVideoTaskStatus.Pending -> "等待中"
                CloudVideoTaskStatus.Running -> if (isRunning) "处理中" else "处理中断"
                CloudVideoTaskStatus.Paused -> "已暂停"
                CloudVideoTaskStatus.Completed -> "已完成"
                CloudVideoTaskStatus.Failed -> "失败"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.fileName,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        color = when (status) {
                            CloudVideoTaskStatus.Failed -> Color(0xFFFF8A80)
                            CloudVideoTaskStatus.Running -> Color(0xFF80D8FF)
                            CloudVideoTaskStatus.Pending, CloudVideoTaskStatus.Paused -> Color(0xFFFFD180)
                            CloudVideoTaskStatus.Completed -> MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                task.failureReason?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(
                        text = reason,
                        color = Color(0xFFFFAB91),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrapeIssueList(
    movies: List<MovieEntity>,
    isRunning: Boolean
) {
    if (movies.isEmpty()) {
        Text(
            text = "暂无刮削失败或未刮削的文件。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = movies, key = MovieEntity::id) { movie ->
            ScrapeIssueCard(movie = movie, isRunning = isRunning)
        }
    }
}

@Composable
private fun ScrapeIssueCard(movie: MovieEntity, isRunning: Boolean) {
    val status = runCatching { ScrapeTaskStatus.valueOf(movie.scrapeTaskStatus) }
        .getOrDefault(ScrapeTaskStatus.None)
    val statusText = when (status) {
        ScrapeTaskStatus.Failed -> "刮削失败"
        ScrapeTaskStatus.Running -> if (isRunning) "正在刮削" else "处理中断"
        ScrapeTaskStatus.Pending -> "待刮削"
        ScrapeTaskStatus.Completed -> "缺少 NFO"
        ScrapeTaskStatus.None -> "未刮削"
    }
    val statusColor = when (status) {
        ScrapeTaskStatus.Failed -> Color(0xFFFF8A80)
        ScrapeTaskStatus.Running -> Color(0xFF80D8FF)
        ScrapeTaskStatus.Pending -> Color(0xFFFFD180)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = movie.videoName.ifBlank { movie.title },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (movie.title.isNotBlank() && movie.title != movie.videoName) {
            Text(
                text = movie.title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        movie.scrapeFailureReason?.takeIf(String::isNotBlank)?.let { reason ->
            Text(
                text = reason,
                color = Color(0xFFFFAB91),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "待执行 $pending · 运行中 $running · 已暂停 $paused · 失败 $failed · 已完成 $completed",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, enabled = !uiState.isCloudFolderBatchRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新网盘文件夹任务", tint = MaterialTheme.colorScheme.onSurface)
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
private fun CloudFolderBatchTaskList(
    tasks: List<CloudFolderBatchTaskEntity>,
    isRunning: Boolean
) {
    val activeTasks = tasks.filter { it.status != CloudFolderBatchTaskStatus.Completed.name }
    if (activeTasks.isEmpty()) {
        Text(
            text = "暂无未完成的文件夹发现任务。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        activeTasks.forEach { task ->
            CloudFolderBatchTaskRow(task = task, runnerRunning = isRunning)
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
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.statusLabel(runnerRunning),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "入库 ${task.addedVideos} · 跳过 ${task.skippedVideos} · 刮削失败 ${task.scrapeFailedVideos} · 失败 ${task.failedVideos + task.failedFolders}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        task.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
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
    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
)

@Composable
private fun taskPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
)

internal data class LibraryScanButtonPalette(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color
)

@Composable
internal fun libraryScanButtonPalette() = LibraryScanButtonPalette(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
)

@Composable
private fun taskCancelButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF7B2E2E),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sources.joinToString(" -> ") { it.label },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "DMM、DMM2、TheJavDB刮削都需要日本节点",
            color = MaterialTheme.colorScheme.error,
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (prefixes.isEmpty()) {
                        "未设置。只影响 DMM2 刮削方式。"
                    } else {
                        "已设置 ${prefixes.size} 个：${prefixes.joinToString("、")}"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
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
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = "115 二维码登录",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "扫码成功后会自动保存 Cookie，并写入文件 115cookie_userId_app.txt。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = "登录方式", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
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
                    .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp))
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.cloud115QrStatusText.isNotBlank()) {
            Text(
                text = uiState.cloud115QrStatusText,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.cloud115QrSavedFile?.let { path ->
            Text(
                text = "保存位置：$path",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
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
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除 115 账号？", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    text = "确定删除账号「${account.displayName}」的本地 Cookie 文件吗？删除后不会影响 115 网盘真实账号。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = "账号", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "图片缓存",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sizeText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回设置", tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (selected) title else emptyText,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (selected) "已选择" else "未配置",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    focusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    focusedSupportingTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
    unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
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
