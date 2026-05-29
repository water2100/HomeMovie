package com.example.localmovielibrary.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.scraper.ScrapeSource

private val SettingsBackground = Color(0xFF070A0E)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenScrapeLogs: () -> Unit,
    onOpenMissavWeb: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val strmDirectoryPicker = rememberTreePicker { uri -> viewModel.saveStrmDirectory(uri) }
    val libraryDirectoryPicker = rememberTreePicker { uri -> viewModel.scanLibrary(uri) }
    var showStrmBaseUrlDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                SettingsSectionTitle("影片库目录")
                DirectorySummary(
                    title = uiState.libraryRootDisplayName,
                    selected = uiState.libraryRootUri != null,
                    emptyText = "尚未选择影片库目录"
                )
                Button(
                    onClick = { libraryDirectoryPicker.launch(treeIntent()) },
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
                    onClick = viewModel::reorganizeExistingLibraries,
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
                    onClick = { strmDirectoryPicker.launch(treeIntent()) },
                    enabled = !uiState.isScraping,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Text("选择 STRM 保存目录", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::rebuildCloudStrmIndex,
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

                SettingsSectionTitle("STRM 入口地址")
                OutlinedButton(
                    onClick = { showStrmBaseUrlDialog = true },
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
                DirectorySummary(
                    title = "A目录：${DomesticMovieRepository.A_DIRECTORY_CID}",
                    selected = true,
                    emptyText = ""
                )

                SettingsSectionTitle("默认刮削")
                DefaultScrapeSourceRow(
                    selected = uiState.defaultScrapeSource,
                    onSelected = viewModel::updateDefaultScrapeSource
                )

                SettingsSectionTitle("图片下载")
                OutlinedTextField(
                    value = uiState.imageDownloadRetryCountText,
                    onValueChange = viewModel::updateImageDownloadRetryCount,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("图片下载重试次数") },
                    supportingText = { Text("建议 3 到 6 次。DMM 图片地址优先请求。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = settingsTextFieldColors()
                )

                SettingsSectionTitle("百度翻译")
                Text(
                    text = "用于后续实时字幕：ASR 识别出的文本会发送到百度翻译，音频不会发送给百度翻译接口。",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = uiState.baiduTranslateAppId,
                    onValueChange = viewModel::updateBaiduTranslateAppId,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("百度翻译 APP ID") },
                    colors = settingsTextFieldColors()
                )
                OutlinedTextField(
                    value = uiState.baiduTranslateSecretKey,
                    onValueChange = viewModel::updateBaiduTranslateSecretKey,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("百度翻译密钥") },
                    colors = settingsTextFieldColors()
                )

                SettingsSectionTitle("刮削日志")
                Text(
                    text = "未刮削 STRM 可以在影片详情页的“更多”中手动刮削；网盘添加影片时会使用默认刮削来源。",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
                ScrapeLogPanel(
                    log = uiState.scrapeLog,
                    onClear = viewModel::clearScrapeLog,
                    onOpenLogs = onOpenScrapeLogs
                )

                SettingsSectionTitle("115 Cookie")
                OutlinedTextField(
                    value = uiState.cookies,
                    onValueChange = viewModel::updateCookies,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("不填写时会使用 App 内置的 115-cookies.txt") },
                    colors = settingsTextFieldColors()
                )

                SettingsSectionTitle("MissAV Cookie")
                MissavCookieStatusCard(
                    hasCookie = uiState.hasMissavCookie,
                    onOpenMissavWeb = onOpenMissavWeb
                )

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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        if (showStrmBaseUrlDialog) {
            StrmBaseUrlDialog(
                value = uiState.strmBaseUrl,
                onValueChange = viewModel::updateBaseUrl,
                onDismiss = { showStrmBaseUrlDialog = false }
            )
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
private fun SettingsTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), SettingsBackground)))
            .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White)
        Text(
            text = "设置",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
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
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            onPicked(uri)
        }
    }

private fun treeIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

private val ScrapeSource.label: String
    get() = when (this) {
        ScrapeSource.Dmm -> "DMM"
        ScrapeSource.Dmm2 -> "DMM2"
        ScrapeSource.Official -> "Official"
        ScrapeSource.Missav -> "MissAV"
    }
