package com.example.localmovielibrary.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.ui.shared.HiddenMissavWebView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CloudBackground = Color(0xFF070A0E)
private val CloudPanel = Color.White.copy(alpha = 0.075f)

@Composable
fun CloudBrowserScreen(
    viewModel: CloudBrowserViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onMovieAdded: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listStates = remember { mutableMapOf<Long, LazyListState>() }
    val currentFolderCid = uiState.path.lastOrNull()?.cid ?: 0L
    val listState = remember(currentFolderCid) {
        listStates.getOrPut(currentFolderCid) { LazyListState() }
    }

    fun handleBack() {
        if (!viewModel.goBackFolder()) {
            onBack()
        }
    }

    BackHandler(onBack = ::handleBack)

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.openMovieId) {
        val movieId = uiState.openMovieId ?: return@LaunchedEffect
        viewModel.consumeOpenMovie()
        onMovieAdded(movieId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudBackground)
    ) {
        uiState.hiddenMissavRequest?.let { request ->
            HiddenMissavWebView(
                request = request,
                onHtmlReady = viewModel::onHiddenMissavHtmlReady,
                onFailed = viewModel::onHiddenMissavFailed
            )
        }
        uiState.pendingReplaceConflict?.let { conflict ->
            AlertDialog(
                onDismissRequest = viewModel::dismissReplaceConflict,
                title = { Text("发现同番号影片") },
                text = {
                    Text(
                        "已存在：${conflict.oldFileName}\n\n新视频：${conflict.item.name}\n\n是否用新视频替换原来的播放源？选择“作为新片”会重新刮削并作为两部不同影片入库。"
                    )
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmReplacePickcode) {
                        Text("替换")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::addConflictAsNewMovie) {
                            Text("作为新片")
                        }
                        Button(onClick = viewModel::dismissReplaceConflict) {
                            Text("取消")
                        }
                    }
                }
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            CloudTopBar(
                path = uiState.path,
                canGoBackFolder = viewModel.canGoBackFolder(),
                onBack = ::handleBack,
                sortAscending = uiState.sortAscending,
                onToggleSortDirection = viewModel::toggleSortDirection
            )

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                uiState.errorMessage != null -> CloudMessage(
                    text = uiState.errorMessage.orEmpty(),
                    action = "去设置",
                )
                uiState.items.isEmpty() -> CloudMessage(text = "这个目录是空的")
                else -> CloudFileList(
                    items = uiState.items,
                    listState = listState,
                    generatingCid = uiState.generatingCid,
                    addingPickcodes = uiState.addingPickcodes,
                    addedPickcodes = uiState.addedPickcodes,
                    isDomesticRoot = currentFolderCid == DomesticMovieRepository.A_DIRECTORY_CID,
                    addingDomesticFolderCids = uiState.addingDomesticFolderCids,
                    addedDomesticFolderCids = uiState.addedDomesticFolderCids,
                    onOpenFolder = viewModel::openFolder,
                    onPlayVideo = onPlayVideo,
                    onAddVideo = viewModel::addVideoToLibrary,
                    onAddDomesticFolder = viewModel::addDomesticFolder
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun CloudTopBar(
    path: List<CloudPathItem>,
    canGoBackFolder: Boolean,
    onBack: () -> Unit,
    sortAscending: Boolean,
    onToggleSortDirection: () -> Unit,
    onSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), CloudBackground)))
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = if (canGoBackFolder) "返回上一级目录" else "返回影片",
                    tint = Color.White
                )
            }
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = Color.White.copy(alpha = 0.9f))
            Text(
                text = "115 网盘",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            )
            IconButton(onClick = onToggleSortDirection) {
                Icon(
                    imageVector = if (sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = if (sortAscending) "淇敼鏃堕棿姝ｅ簭" else "淇敼鏃堕棿鍊掑簭",
                    tint = Color.White
                )
            }
        }
        Text(
            text = path.joinToString(" / ") { it.name },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

@Composable
private fun LegacyCloudTopBar(
    path: List<CloudPathItem>,
    canGoBackFolder: Boolean,
    onBack: () -> Unit,
    sortAscending: Boolean,
    onToggleSortDirection: () -> Unit,
    onSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), CloudBackground)))
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = if (canGoBackFolder) "返回上一级目录" else "返回影片",
                    tint = Color.White
                )
            }
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = Color.White.copy(alpha = 0.9f))
            Text(
                text = "115 网盘",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            )
            IconButton(onClick = onToggleSortDirection) {
                Icon(Icons.Rounded.Refresh, contentDescription = "鍒锋柊", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "璁剧疆", tint = Color.White)
            }
        }
        Text(
            text = path.joinToString(" / ") { it.name },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

@Composable
private fun CloudFileList(
    items: List<Cloud115FileItem>,
    listState: LazyListState,
    generatingCid: Long?,
    addingPickcodes: Set<String>,
    addedPickcodes: Set<String>,
    isDomesticRoot: Boolean,
    addingDomesticFolderCids: Set<Long>,
    addedDomesticFolderCids: Set<Long>,
    onOpenFolder: (Cloud115FileItem) -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onAddVideo: (Cloud115FileItem) -> Unit,
    onAddDomesticFolder: (Cloud115FileItem) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        items(items, key = { "${it.cid}-${it.fid}-${it.name}" }) { item ->
            CloudFileRow(
                item = item,
                isGenerating = item.cid != null && generatingCid == item.cid,
                isAdding = item.pickcode != null && item.pickcode in addingPickcodes,
                isAdded = item.pickcode != null && item.pickcode in addedPickcodes,
                showDomesticAdd = isDomesticRoot && item.isDirectory && item.cid != null,
                isDomesticAdding = item.cid != null && item.cid in addingDomesticFolderCids,
                isDomesticAdded = item.cid != null && item.cid in addedDomesticFolderCids,
                onOpenFolder = { onOpenFolder(item) },
                onPlayVideo = { onPlayVideo(item) },
                onAddVideo = { onAddVideo(item) },
                onAddDomesticFolder = { onAddDomesticFolder(item) }
            )
        }
    }
}

@Composable
private fun CloudFileRow(
    item: Cloud115FileItem,
    isGenerating: Boolean,
    isAdding: Boolean,
    isAdded: Boolean,
    showDomesticAdd: Boolean,
    isDomesticAdding: Boolean,
    isDomesticAdded: Boolean,
    onOpenFolder: () -> Unit,
    onPlayVideo: () -> Unit,
    onAddVideo: () -> Unit,
    onAddDomesticFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CloudPanel)
            .then(
                when {
                    item.isDirectory -> Modifier.clickable(onClick = onOpenFolder)
                    item.isVideoFile() -> Modifier.clickable(onClick = onPlayVideo)
                    else -> Modifier
                }
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.VideoFile,
                contentDescription = null,
                tint = if (item.isDirectory) Color(0xFFE7C267) else Color.White.copy(alpha = 0.76f)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.cloudSubtitle(),
                color = Color.White.copy(alpha = 0.56f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            showDomesticAdd -> AddVideoButton(
                isAdding = isDomesticAdding,
                isAdded = isDomesticAdded,
                onAddVideo = onAddDomesticFolder
            )
            item.isDirectory -> Unit
            item.isVideoFile() -> AddVideoButton(isAdding = isAdding, isAdded = isAdded, onAddVideo = onAddVideo)
        }
    }
}

@Composable
private fun FolderGenerateButton(isGenerating: Boolean, onGenerate: () -> Unit) {
    Button(
        onClick = onGenerate,
        enabled = !isGenerating,
        shape = RoundedCornerShape(18.dp)
    ) {
        if (isGenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.Black
            )
        } else {
            Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(6.dp))
            Text("鐢熸垚")
        }
    }
}

@Composable
private fun AddVideoButton(isAdding: Boolean, isAdded: Boolean, onAddVideo: () -> Unit) {
    if (isAdded) {
        Text(
            text = "已添加",
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
    if (isAdding) {
        Text(
            text = "添加中",
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
    Button(
        onClick = onAddVideo,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text("添加", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CloudMessage(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.74f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (action != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun formatSize(size: Long?): String {
    val value = size ?: return "\u6587\u4EF6"
    if (value <= 0L) return "\u6587\u4EF6"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = 0
    while (amount >= 1024 && index < units.lastIndex) {
        amount /= 1024
        index += 1
    }
    return "%.1f %s".format(amount, units[index])
}

private fun formatCloudTime(timestampMillis: Long): String {
    val safeMillis = if (timestampMillis < 10_000_000_000L) timestampMillis * 1000L else timestampMillis
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(safeMillis))
}

private fun Cloud115FileItem.cloudSubtitle(): String {
    val time = modifiedAt?.let { formatCloudTime(it) } ?: "\u65F6\u95F4\u672A\u77E5"
    return if (isDirectory) {
        time
    } else {
        "${formatSize(size)} · $time"
    }
}

private fun Cloud115FileItem.isVideoFile(): Boolean =
    videoExtensions.any { name.endsWith(it, ignoreCase = true) }

private val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".ts", ".iso", ".flv", ".webm")
