package com.example.localmovielibrary.ui.cloud

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.ui.shared.HiddenMissavWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CloudBackground = Color(0xFF070A0E)
private val CloudPanel = Color.White.copy(alpha = 0.075f)

@Composable
fun CloudBrowserScreen(
    viewModel: CloudBrowserViewModel,
    onBack: () -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onMovieAdded: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingBatchFolder by remember { mutableStateOf<Cloud115FileItem?>(null) }
    val currentFolderCid = uiState.path.lastOrNull()?.cid ?: 0L
    val initialScrollPosition = remember(currentFolderCid) {
        viewModel.scrollPositionFor(currentFolderCid)
    }
    val listState = rememberSaveable(currentFolderCid, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = initialScrollPosition.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialScrollPosition.firstVisibleItemScrollOffset
        )
    }

    fun handleBack() {
        if (!viewModel.goBackFolder()) {
            onBack()
        }
    }

    BackHandler(onBack = ::handleBack)

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            val snackbarJob = launch { snackbarHostState.showSnackbar(it) }
            delay(1_600)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob.cancel()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.openMovieId) {
        val movieId = uiState.openMovieId ?: return@LaunchedEffect
        viewModel.consumeOpenMovie()
        onMovieAdded(movieId)
    }

    LaunchedEffect(uiState.scrollResetVersion, currentFolderCid) {
        if (uiState.scrollResetVersion > 0) {
            yield()
            listState.scrollToItem(0)
            viewModel.saveScrollPosition(currentFolderCid, 0, 0)
        }
    }

    LaunchedEffect(currentFolderCid, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.saveScrollPosition(currentFolderCid, index, offset)
        }
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
        pendingBatchFolder?.let { folder ->
            AlertDialog(
                onDismissRequest = { pendingBatchFolder = null },
                title = { Text("添加整个文件夹？") },
                text = {
                    Text(
                        "确认后会递归读取“${folder.name}”内的视频，并按当前默认刮削方式逐个加入队列。无法提取番号、命中排除名单或小于设置大小阈值的视频会直接跳过。"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingBatchFolder = null
                            viewModel.addFolderVideosToLibrary(folder)
                        }
                    ) {
                        Text("开始添加")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBatchFolder = null }) {
                        Text("取消")
                    }
                }
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            CloudTopBar(
                path = uiState.path,
                canGoBackFolder = viewModel.canGoBackFolder(),
                onBack = ::handleBack,
                sortOption = uiState.sortOption,
                onSortOptionSelected = viewModel::setSortOption,
                sortAscending = uiState.sortAscending,
                onToggleSortDirection = viewModel::toggleSortDirection
            )

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                uiState.errorMessage != null -> CloudMessage(text = uiState.errorMessage.orEmpty())
                uiState.items.isEmpty() -> CloudMessage(text = "这个目录是空的")
                else -> CloudFileList(
                    items = uiState.items,
                    listState = listState,
                    addingPickcodes = uiState.addingPickcodes,
                    addedPickcodes = uiState.addedPickcodes,
                    excludedVideoNames = uiState.excludedVideoNames,
                    isDomesticRoot = viewModel.domesticRootCid()?.let { currentFolderCid == it } == true,
                    addingDomesticFolderCids = uiState.addingDomesticFolderCids,
                    addedDomesticFolderCids = uiState.addedDomesticFolderCids,
                    addingFolderCids = uiState.addingFolderCids,
                    addedFolderCids = uiState.addedFolderCids,
                    onOpenFolder = viewModel::openFolder,
                    onPlayVideo = onPlayVideo,
                    onAddVideo = viewModel::addVideoToLibrary,
                    onAddFolder = { pendingBatchFolder = it },
                    onAddDomesticFolder = viewModel::addDomesticFolder
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp, start = 16.dp, end = 16.dp)
        )
    }
}

@Composable
private fun CloudTopBar(
    path: List<CloudPathItem>,
    canGoBackFolder: Boolean,
    onBack: () -> Unit,
    sortOption: CloudSortOption,
    onSortOptionSelected: (CloudSortOption) -> Unit,
    sortAscending: Boolean,
    onToggleSortDirection: () -> Unit
) {
    var sortMenuExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF101923), CloudBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = if (canGoBackFolder) "返回上一级目录" else "返回影片",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Icon(
                Icons.Rounded.Cloud,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "115 网盘",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            Box {
                Text(
                    text = sortOption.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable { sortMenuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    CloudSortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOptionSelected(option)
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onToggleSortDirection, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = "${sortOption.label}${if (sortAscending) "正序" else "倒序"}",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = path.joinToString(" / ") { it.name },
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun CloudFileList(
    items: List<Cloud115FileItem>,
    listState: LazyListState,
    addingPickcodes: Set<String>,
    addedPickcodes: Set<String>,
    excludedVideoNames: Set<String>,
    isDomesticRoot: Boolean,
    addingDomesticFolderCids: Set<Long>,
    addedDomesticFolderCids: Set<Long>,
    addingFolderCids: Set<Long>,
    addedFolderCids: Set<Long>,
    onOpenFolder: (Cloud115FileItem) -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onAddVideo: (Cloud115FileItem) -> Unit,
    onAddFolder: (Cloud115FileItem) -> Unit,
    onAddDomesticFolder: (Cloud115FileItem) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        items(
            items = items,
            key = { it.cloudStableKey() },
            contentType = {
                when {
                    it.isDirectory -> "cloud-folder"
                    it.isVideoFile() -> "cloud-video"
                    else -> "cloud-file"
                }
            }
        ) { item ->
            CloudFileRow(
                item = item,
                isAdding = item.pickcode != null && item.pickcode in addingPickcodes,
                isAdded = item.pickcode != null && item.pickcode in addedPickcodes,
                isExcludedVideo = item.isVideoFile() && item.name.trim() in excludedVideoNames,
                showDomesticAdd = isDomesticRoot && item.isDirectory && item.cid != null,
                isDomesticAdding = item.cid != null && item.cid in addingDomesticFolderCids,
                isDomesticAdded = item.cid != null && item.cid in addedDomesticFolderCids,
                isFolderAdding = item.cid != null && item.cid in addingFolderCids,
                isFolderAdded = item.cid != null && item.cid in addedFolderCids,
                onOpenFolder = { onOpenFolder(item) },
                onPlayVideo = { onPlayVideo(item) },
                onAddVideo = { onAddVideo(item) },
                onAddFolder = { onAddFolder(item) },
                onAddDomesticFolder = { onAddDomesticFolder(item) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudFileRow(
    item: Cloud115FileItem,
    isAdding: Boolean,
    isAdded: Boolean,
    isExcludedVideo: Boolean,
    showDomesticAdd: Boolean,
    isDomesticAdding: Boolean,
    isDomesticAdded: Boolean,
    isFolderAdding: Boolean,
    isFolderAdded: Boolean,
    onOpenFolder: () -> Unit,
    onPlayVideo: () -> Unit,
    onAddVideo: () -> Unit,
    onAddFolder: () -> Unit,
    onAddDomesticFolder: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val subtitle = remember(item.size, item.modifiedAt, item.isDirectory) {
        item.cloudSubtitle()
    }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val folderCid = item.cid?.toString().orEmpty()

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CloudPanel)
                .then(
                    when {
                        item.isDirectory -> Modifier.combinedClickable(
                            onClick = onOpenFolder,
                            onLongClick = {
                                if (folderCid.isNotBlank()) folderMenuExpanded = true
                            }
                        )
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
                    text = subtitle,
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
                item.isDirectory && item.cid != null -> AddVideoButton(
                    isAdding = isFolderAdding,
                    isAdded = isFolderAdded,
                    onAddVideo = onAddFolder
                )
                item.isVideoFile() && !isExcludedVideo -> AddVideoButton(isAdding = isAdding, isAdded = isAdded, onAddVideo = onAddVideo)
            }
        }

        DropdownMenu(
            expanded = folderMenuExpanded,
            onDismissRequest = { folderMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制 CID") },
                onClick = {
                    folderMenuExpanded = false
                    if (folderCid.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(folderCid))
                        Toast.makeText(context, "已复制 CID：$folderCid", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

private val CloudSortOption.label: String
    get() = when (this) {
        CloudSortOption.ModifiedTime -> "时间"
        CloudSortOption.Size -> "大小"
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

private fun Cloud115FileItem.cloudStableKey(): String =
    when {
        pickcode != null -> "pc:$pickcode"
        fid != null -> "fid:$fid"
        cid != null -> "cid:$cid"
        else -> "name:$name"
    }

@Composable
private fun CloudMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.74f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
