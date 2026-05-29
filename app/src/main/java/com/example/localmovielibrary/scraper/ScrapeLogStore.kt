package com.example.localmovielibrary.scraper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrapeLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scraper_logs", Context.MODE_PRIVATE)
    private val logCache = mutableMapOf<String, String>()
    private val _updates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = _updates

    @Synchronized
    fun dates(): List<String> {
        val dates = prefs.getStringSet(KEY_DATES, emptySet()).orEmpty()
        return dates.sortedDescending().ifEmpty { listOf(today()) }
    }

    @Synchronized
    fun read(date: String = today()): String =
        logCache.getOrPut(date) { prefs.getString(logKey(date), null).orEmpty() }

    @Synchronized
    fun clear(date: String = today()) {
        logCache.remove(date)
        prefs.edit().remove(logKey(date)).apply()
        _updates.update { it + 1 }
    }

    @Synchronized
    fun clearAll() {
        logCache.clear()
        prefs.edit().clear().apply()
        _updates.update { it + 1 }
    }

    @Synchronized
    fun append(message: String) {
        val date = today()
        val line = "[${time()}] $message"
        val oldLines = read(date).lines().filter { it.isNotBlank() }.take(MAX_LINES - 1)
        val next = (listOf(line) + oldLines).joinToString("\n")
        val dates = prefs.getStringSet(KEY_DATES, emptySet()).orEmpty().toMutableSet().apply { add(date) }
        logCache[date] = next
        prefs.edit()
            .putString(logKey(date), next)
            .putStringSet(KEY_DATES, dates)
            .apply()
        _updates.update { it + 1 }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun time(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun logKey(date: String): String = "log_$date"

    private companion object {
        const val KEY_DATES = "dates"
        const val MAX_LINES = 600
    }
}
