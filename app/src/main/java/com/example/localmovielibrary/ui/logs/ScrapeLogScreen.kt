package com.example.localmovielibrary.ui.logs

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val LogBackground = Color(0xFF070A0E)

@Composable
fun ScrapeLogScreen(
    viewModel: ScrapeLogViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LogBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF101923), LogBackground)))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = "刮削日志",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = Color.White)
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
                onDateSelected = viewModel::selectDate
            )

            LogActionRow(
                log = uiState.log,
                selectedDate = uiState.selectedDate,
                onCopy = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                },
                onShare = { text, date ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "刮削日志 $date")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享日志"))
                },
                onClear = viewModel::clearSelected
            )

            LogContent(
                uiState = uiState,
                onLoadMore = viewModel::loadMore
            )
        }
    }
}

@Composable
private fun LogContent(
    uiState: ScrapeLogUiState,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        when {
            uiState.isLoading -> LogText("正在读取日志...")
            uiState.visibleLines.isEmpty() -> LogText("当天暂无日志")
            else -> LogTextContent(
                text = uiState.visibleLines.joinToString("\n"),
                hasMoreLines = uiState.hasMoreLines,
                visibleLineCount = uiState.visibleLineCount,
                totalLineCount = uiState.totalLineCount,
                onLoadMore = onLoadMore
            )
        }
    }
}

@Composable
private fun LogTextContent(
    text: String,
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
            LogText(text)
        }
        if (hasMoreLines) {
            OutlinedButton(
                onClick = onLoadMore,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("加载更多 $visibleLineCount / $totalLineCount")
            }
        }
    }
}

@Composable
private fun LogText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.78f),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun LogActionRow(
    log: String,
    selectedDate: String,
    onCopy: (String) -> Unit,
    onShare: (String, String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = { onCopy(log) },
            enabled = log.isNotBlank(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("复制日志")
        }
        OutlinedButton(
            onClick = { onShare(log, selectedDate) },
            enabled = log.isNotBlank(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("分享")
        }
        OutlinedButton(onClick = onClear, shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
            Text("清空", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DateSelector(
    dates: List<String>,
    selectedDate: String,
    onDateSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
}
