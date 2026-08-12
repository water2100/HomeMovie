package com.example.localmovielibrary.ui.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.scraper.ScrapeEventLevel
import com.example.localmovielibrary.scraper.ScrapeTaskReport
import com.example.localmovielibrary.scraper.ScrapeTaskStatus
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LogBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background

@Composable
fun ScrapeLogScreen(
    viewModel: ScrapeLogViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val exportScope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<LogExportRequest?>(null) }
    var legacyCleanupStep by rememberSaveable { mutableStateOf(0) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.bufferedWriter(StandardCharsets.UTF_8)
                        ?.use { writer -> writer.write(request.content) }
                        ?: error("无法打开导出文件")
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) "${request.date} 日志已导出" else "日志导出失败：${result.exceptionOrNull()?.message.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    if (legacyCleanupStep == 1) {
        AlertDialog(
            onDismissRequest = { legacyCleanupStep = 0 },
            title = { Text("清理旧格式日志？") },
            text = { Text("将扫描所有日期，仅删除无法按当前番号日志格式归类的历史内容。按番号保存的日志不会受影响。") },
            confirmButton = {
                TextButton(onClick = { legacyCleanupStep = 2 }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { legacyCleanupStep = 0 }) { Text("取消") }
            }
        )
    }
    if (legacyCleanupStep == 2) {
        AlertDialog(
            onDismissRequest = { legacyCleanupStep = 0 },
            title = { Text("确认永久删除？") },
            text = { Text("删除后无法恢复。这不会清空当前按番号显示的刮削日志。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        legacyCleanupStep = 0
                        viewModel.removeLegacyLogs { removedCount ->
                            Toast.makeText(context, "已删除 $removedCount 条旧格式日志", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { legacyCleanupStep = 0 }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .background(LogBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, LogBackground)))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = "刮削日志",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DateSelector(
                dates = uiState.dates,
                selectedDate = uiState.selectedDate,
                onDateSelected = viewModel::selectDate,
                onCleanLegacy = { legacyCleanupStep = 1 }
            )

            LogStatusSummary(uiState.allNumberLogs)

            LogActionRow(
                log = uiState.log,
                selectedDate = uiState.selectedDate,
                onExport = { text, date ->
                    pendingExport = LogExportRequest(date = date, content = text)
                    exportLauncher.launch(logExportFileName(date))
                },
                onClear = viewModel::clearSelected
            )

            LogContent(
                uiState = uiState,
                onLoadMore = viewModel::loadMore,
                onCopyNumberLog = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.LogContent(
    uiState: ScrapeLogUiState,
    onLoadMore: () -> Unit,
    onCopyNumberLog: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            uiState.isLoading -> LogText("正在读取日志...")
            uiState.visibleNumberLogs.isEmpty() -> LogText("当天暂无影片刮削日志")
            else -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NumberLogContent(
                        logs = uiState.visibleNumberLogs,
                        hasMoreLogs = uiState.hasMoreLogs,
                        visibleLogCount = uiState.visibleLogCount,
                        totalLogCount = uiState.totalLogCount,
                        onLoadMore = onLoadMore,
                        onCopy = onCopyNumberLog
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberLogContent(
    logs: List<NumberScrapeLog>,
    hasMoreLogs: Boolean,
    visibleLogCount: Int,
    totalLogCount: Int,
    onLoadMore: () -> Unit,
    onCopy: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            logs.forEach { log -> NumberLogItem(log = log, onCopy = onCopy) }
        }
        OutlinedButton(
            onClick = onLoadMore,
            enabled = hasMoreLogs,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (hasMoreLogs) 0.35f else 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
            )
        ) {
            Text(if (hasMoreLogs) "加载更多 $visibleLogCount / $totalLogCount" else "已显示全部 $visibleLogCount / $totalLogCount")
        }
    }
}

@Composable
private fun NumberLogItem(log: NumberScrapeLog, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable(log.number, log.events.firstOrNull()?.timestamp) { mutableStateOf(false) }
    val statusColor = when (log.status) {
        NumberScrapeLogStatus.Success -> Color(0xFF80CBC4)
        NumberScrapeLogStatus.Warning -> Color(0xFFFFD180)
        NumberScrapeLogStatus.Failed -> Color(0xFFFF8A80)
        NumberScrapeLogStatus.Running -> Color(0xFF90CAF9)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .background(statusColor, RoundedCornerShape(4.dp))
            )
            Text(
                text = log.number,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = { onCopy(log.copyText()) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("复制", style = MaterialTheme.typography.labelSmall) }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                log.events.forEach { event -> NumberLogEventLine(event) }
            }
        }
    }
}

@Composable
private fun NumberLogEventLine(event: NumberScrapeLogEvent) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = event.timestamp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(68.dp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        Text(
            text = event.message,
            color = event.message.logLineColor(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScrapeTaskCard(report: ScrapeTaskReport) {
    var expanded by rememberSaveable(report.taskId) { mutableStateOf(false) }
    val statusColor = when (report.status) {
        ScrapeTaskStatus.Succeeded -> Color(0xFF80CBC4)
        ScrapeTaskStatus.Failed -> Color(0xFFFF8A80)
        ScrapeTaskStatus.Running -> Color(0xFFFFD180)
    }
    val statusText = when (report.status) {
        ScrapeTaskStatus.Succeeded -> "成功"
        ScrapeTaskStatus.Failed -> "失败"
        ScrapeTaskStatus.Running -> "进行中"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(statusColor.copy(alpha = 0.12f))
            .clickable { expanded = !expanded }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$statusText · ${report.number}", color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "详情", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
        Text("${report.operation} · ${report.source}" + report.durationMillis?.let { " · ${formatTaskDuration(it)}" }.orEmpty(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            report.events.forEach { event ->
                val color = when (event.level) {
                    ScrapeEventLevel.Success -> Color(0xFF80CBC4)
                    ScrapeEventLevel.Warning -> Color(0xFFFFD180)
                    ScrapeEventLevel.Error -> Color(0xFFFF8A80)
                    ScrapeEventLevel.Info -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                }
                Text("${event.stage} · ${event.message}", color = color, style = MaterialTheme.typography.bodySmall)
            }
            Text("任务 #${report.taskId}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatTaskDuration(durationMillis: Long): String =
    if (durationMillis < 1_000) "< 1 秒" else "%.1f 秒".format(durationMillis / 1_000.0)

@Composable
private fun LogTextContent(
    lines: List<String>,
    hasMoreLines: Boolean,
    visibleLineCount: Int,
    totalLineCount: Int,
    onLoadMore: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Column {
                lines.forEach { line ->
                    if ("【番号分隔】" in line) {
                        MovieNumberDivider(line)
                    } else {
                        AlignedLogLine(line)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onLoadMore,
            enabled = hasMoreLines,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (hasMoreLines) {
                    "加载更多日志 $visibleLineCount / $totalLineCount"
                } else {
                    "已显示全部日志 $visibleLineCount / $totalLineCount"
                }
            )
        }
    }
}

@Composable
private fun AlignedLogLine(line: String) {
    val timestampEnd = line.indexOf("] ").takeIf { it > 0 && line.startsWith("[") }
    if (timestampEnd == null) {
        Text(text = line, color = line.logLineColor(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        return
    }
    val timestamp = line.substring(0, timestampEnd + 1)
    val message = line.substring(timestampEnd + 2)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = timestamp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        Text(
            text = message,
            color = line.logLineColor(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MovieNumberDivider(line: String) {
    val time = line.substringBefore("【番号分隔】").trim()
    val number = line.substringAfter("【番号分隔】").trim()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (time.isNotEmpty()) {
            Text(time, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF80CBC4).copy(alpha = 0.48f))
        Text(
            text = "番号 $number",
            color = Color(0xFF80CBC4),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF80CBC4).copy(alpha = 0.48f))
    }
}

@Composable
private fun String.logLineColor(): Color {
    val normalized = lowercase()
    return when {
        listOf("failed", "error", "exception", "失败", "错误", "异常", "http 4", "http 5")
            .any(normalized::contains) -> Color(0xFFFF8A80)
        listOf("warning", "warn", "skipped", "retry", "跳过", "警告", "重试", "未刮削")
            .any(normalized::contains) -> Color(0xFFFFD180)
        listOf("success", "succeeded", "finished", "completed", "成功", "完成", "已写入")
            .any(normalized::contains) -> Color(0xFF80CBC4)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    }
}

@Composable
private fun LogText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun LogActionRow(
    log: String,
    selectedDate: String,
    onExport: (String, String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = { onExport(log, selectedDate) },
            enabled = log.isNotBlank(),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.FileDownload, contentDescription = null)
            Text("导出日志", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onClear,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
            Text("清空当天", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LogStatusSummary(logs: List<NumberScrapeLog>) {
    val counts = NumberScrapeLogStatus.entries.associateWith { status -> logs.count { it.status == status } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatusCount("成功", counts[NumberScrapeLogStatus.Success] ?: 0, Color(0xFF80CBC4), Modifier.weight(1f))
        StatusCount("失败", counts[NumberScrapeLogStatus.Failed] ?: 0, Color(0xFFFF8A80), Modifier.weight(1f))
        StatusCount("警告", counts[NumberScrapeLogStatus.Warning] ?: 0, Color(0xFFFFD180), Modifier.weight(1f))
        StatusCount("进行中", counts[NumberScrapeLogStatus.Running] ?: 0, Color(0xFF90CAF9), Modifier.weight(1f))
    }
}

@Composable
private fun StatusCount(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(5.dp).height(18.dp).background(color, RoundedCornerShape(4.dp)))
        Text("$label $count", color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun DateSelector(
    dates: List<String>,
    selectedDate: String,
    onDateSelected: (String) -> Unit,
    onCleanLegacy: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "选择要查看或导出的日志日期",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Event, contentDescription = null)
                    Text(selectedDate.ifBlank { "选择日期" }, modifier = Modifier.padding(start = 8.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    dates.forEach { date ->
                        DropdownMenuItem(
                            text = { Text(date) },
                            onClick = {
                                expanded = false
                                onDateSelected(date)
                            }
                        )
                    }
                }
            }
            OutlinedButton(onClick = onCleanLegacy, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Text("清理旧格式", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

private data class LogExportRequest(
    val date: String,
    val content: String
)

internal fun logExportFileName(date: String): String =
    "HomeMovie-刮削日志-${date.ifBlank { "未知日期" }}.txt"
