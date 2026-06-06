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
            prefs.getStringSet(KEY_DATES, emptySet()).orEmpty()
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

    fun append(message: String) {
        val date = today()
        val line = "[${time()}] $message"
        val snapshot = synchronized(lock) {
            val current = logCache.getOrPut(date) { prefs.getString(logKey(date), null).orEmpty() }
            val oldLines = current.lineSequence().filter { it.isNotBlank() }.take(MAX_LINES - 1)
            val next = sequenceOf(line).plus(oldLines).joinToString("\n")
            logCache[date] = next
            val dates = prefs.getStringSet(KEY_DATES, emptySet()).orEmpty().toMutableSet().apply { add(date) }
            LogSnapshot(date = date, log = next, dates = dates)
        }
        writerScope.launch {
            writeMutex.withLock {
                prefs.edit()
                    .putString(logKey(snapshot.date), snapshot.log)
                    .putStringSet(KEY_DATES, snapshot.dates)
                    .apply()
            }
        }
        _updates.update { it + 1 }
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
        const val MAX_LINES = 600
    }
}
