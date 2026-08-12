package com.example.localmovielibrary.scraper

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrapeLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scraper_logs", Context.MODE_PRIVATE)
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val lock = Any()
    private val logCache = mutableMapOf<String, String>()
    private val _updates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = _updates

    fun dates(): List<String> {
        val dates = synchronized(lock) {
            prefs.getStringSet(KEY_DATES, emptySet()).orEmpty() + logCache.keys
        }
        return dates.sortedDescending().ifEmpty { listOf(today()) }
    }

    fun read(date: String = today()): String =
        synchronized(lock) {
            logCache.getOrPut(date) { prefs.getString(logKey(date), null).orEmpty() }
        }

    fun clear(date: String = today()) {
        synchronized(lock) {
            logCache.remove(date)
        }
        writerScope.launch {
            writeMutex.withLock {
                prefs.edit().remove(logKey(date)).apply()
            }
        }
        _updates.update { it + 1 }
    }

    fun clearAll() {
        synchronized(lock) {
            logCache.clear()
        }
        writerScope.launch {
            writeMutex.withLock {
                prefs.edit().clear().apply()
            }
        }
        _updates.update { it + 1 }
    }

    /** Removes historical lines that cannot belong to a per-number log entry. */
    fun removeLegacyLines(): Int {
        val dates = synchronized(lock) {
            prefs.getStringSet(KEY_DATES, emptySet()).orEmpty() + logCache.keys
        }
        var removedCount = 0
        synchronized(lock) {
            dates.forEach { date ->
                val current = logCache.getOrPut(date) { prefs.getString(logKey(date), null).orEmpty() }
                val retainedLines = current.lineSequence()
                    .filter { it.isNotBlank() }
                    .filter(::isNumberLogLine)
                    .toList()
                removedCount += current.lineSequence().count { it.isNotBlank() } - retainedLines.size
                logCache[date] = retainedLines.joinToString("\n")
            }
        }
        if (removedCount == 0) return 0
        writerScope.launch {
            writeMutex.withLock {
                val snapshots = synchronized(lock) {
                    dates.associateWith { date -> logCache[date].orEmpty() }
                }
                prefs.edit().apply {
                    snapshots.forEach { (date, log) -> putString(logKey(date), log) }
                    apply()
                }
            }
        }
        _updates.update { it + 1 }
        return removedCount
    }

    fun append(message: String) {
        val date = today()
        val line = "[${time()}] ${ScrapeLogContext.tag(message)}"
        synchronized(lock) {
            val current = logCache.getOrPut(date) { prefs.getString(logKey(date), null).orEmpty() }
            val oldLines = current.lineSequence().filter { it.isNotBlank() }.take(MAX_LINES - 1)
            val next = sequenceOf(line).plus(oldLines).joinToString("\n")
            logCache[date] = next
        }
        writerScope.launch {
            writeMutex.withLock {
                val snapshot = synchronized(lock) {
                    LogSnapshot(
                        date = date,
                        log = logCache[date].orEmpty(),
                        dates = prefs.getStringSet(KEY_DATES, emptySet()).orEmpty() + logCache.keys
                    )
                }
                prefs.edit()
                    .putString(logKey(snapshot.date), snapshot.log)
                    .putStringSet(KEY_DATES, snapshot.dates)
                    .apply()
            }
        }
        _updates.update { it + 1 }
    }

    fun appendMediaPath(
        operation: String,
        status: String,
        movieId: Long? = null,
        fileName: String? = null,
        databaseUri: String,
        detail: String
    ) {
        append(formatMediaPathLog(operation, status, movieId, fileName, databaseUri, detail))
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun time(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun logKey(date: String): String = "log_$date"

    private data class LogSnapshot(
        val date: String,
        val log: String,
        val dates: Set<String>
    )

    private companion object {
        const val KEY_DATES = "dates"
        const val MAX_LINES = 2_000
    }
}

internal fun formatMediaPathLog(
    operation: String,
    status: String,
    movieId: Long?,
    fileName: String?,
    databaseUri: String,
    detail: String
): String = buildString {
    append("[媒体路径]")
    append(" 状态=").append(status)
    append("，操作=").append(operation)
    movieId?.let { append("，movieId=").append(it) }
    fileName?.takeIf { it.isNotBlank() }?.let { append("，文件=").append(it) }
    append("，详情=").append(detail)
}

private fun isNumberLogLine(line: String): Boolean {
    val message = line.substringAfter("] ", missingDelimiterValue = "")
    return message.startsWith("【番号分隔】") ||
        message.startsWith("【番号=") ||
        NUMBER_IN_LOG_LINE.containsMatchIn(message)
}

private val NUMBER_IN_LOG_LINE = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,12}-\\d{2,6})(?:-[A-Z0-9]+)?")
