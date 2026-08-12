package com.example.localmovielibrary.scraper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A compact, user-facing audit trail for one scrape operation. */
class ScrapeTaskJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scrape_task_journal", Context.MODE_PRIVATE)
    private val _reports = MutableStateFlow(load())
    val reports: StateFlow<List<ScrapeTaskReport>> = _reports

    fun latestForMovie(movieId: Long): ScrapeTaskReport? =
        _reports.value.firstOrNull { it.movieId == movieId }

    fun start(movieId: Long, number: String, fileName: String, operation: String, source: String): String {
        val taskId = UUID.randomUUID().toString().take(8)
        update(
            ScrapeTaskReport(
                taskId = taskId,
                movieId = movieId,
                number = number,
                fileName = fileName,
                operation = operation,
                source = source,
                startedAtMillis = System.currentTimeMillis(),
                status = ScrapeTaskStatus.Running,
                events = listOf(ScrapeTaskEvent("开始", "已进入刮削队列", ScrapeEventLevel.Info))
            )
        )
        return taskId
    }

    fun record(taskId: String, stage: String, message: String, level: ScrapeEventLevel = ScrapeEventLevel.Info) {
        change(taskId) { report -> report.copy(events = report.events + ScrapeTaskEvent(stage, message, level)) }
    }

    fun finish(taskId: String, success: Boolean, message: String) {
        change(taskId) { report ->
            report.copy(
                status = if (success) ScrapeTaskStatus.Succeeded else ScrapeTaskStatus.Failed,
                finishedAtMillis = System.currentTimeMillis(),
                events = report.events + ScrapeTaskEvent(
                    stage = "完成",
                    message = message,
                    level = if (success) ScrapeEventLevel.Success else ScrapeEventLevel.Error
                )
            )
        }
    }

    private fun change(taskId: String, transform: (ScrapeTaskReport) -> ScrapeTaskReport) {
        val current = _reports.value
        val index = current.indexOfFirst { it.taskId == taskId }
        if (index < 0) return
        update(transform(current[index]), current, index)
    }

    private fun update(report: ScrapeTaskReport, current: List<ScrapeTaskReport> = _reports.value, replaceIndex: Int = -1) {
        val next = if (replaceIndex >= 0) current.toMutableList().apply { set(replaceIndex, report) }
        else listOf(report) + current
        val trimmed = next.take(MAX_REPORTS)
        _reports.value = trimmed
        prefs.edit().putString(KEY_REPORTS, JSONArray().apply { trimmed.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun load(): List<ScrapeTaskReport> = runCatching {
        JSONArray(prefs.getString(KEY_REPORTS, "[]"))
            .let { array -> List(array.length()) { index -> ScrapeTaskReport.fromJson(array.getJSONObject(index)) } }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_REPORTS = "reports"
        const val MAX_REPORTS = 100
    }
}

data class ScrapeTaskReport(
    val taskId: String,
    val movieId: Long,
    val number: String,
    val fileName: String,
    val operation: String,
    val source: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val status: ScrapeTaskStatus,
    val events: List<ScrapeTaskEvent>
) {
    val durationMillis: Long? get() = finishedAtMillis?.minus(startedAtMillis)

    fun toJson() = JSONObject().apply {
        put("taskId", taskId); put("movieId", movieId); put("number", number); put("fileName", fileName)
        put("operation", operation); put("source", source); put("startedAtMillis", startedAtMillis)
        put("finishedAtMillis", finishedAtMillis); put("status", status.name)
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject) = ScrapeTaskReport(
            taskId = json.getString("taskId"), movieId = json.getLong("movieId"), number = json.optString("number"),
            fileName = json.optString("fileName"), operation = json.optString("operation"), source = json.optString("source"),
            startedAtMillis = json.getLong("startedAtMillis"),
            finishedAtMillis = json.optLong("finishedAtMillis").takeIf { json.has("finishedAtMillis") && !json.isNull("finishedAtMillis") },
            status = ScrapeTaskStatus.valueOf(json.optString("status", ScrapeTaskStatus.Failed.name)),
            events = json.getJSONArray("events").let { array -> List(array.length()) { ScrapeTaskEvent.fromJson(array.getJSONObject(it)) } }
        )
    }
}

data class ScrapeTaskEvent(val stage: String, val message: String, val level: ScrapeEventLevel) {
    fun toJson() = JSONObject().put("stage", stage).put("message", message).put("level", level.name)
    companion object { fun fromJson(json: JSONObject) = ScrapeTaskEvent(json.optString("stage"), json.optString("message"), ScrapeEventLevel.valueOf(json.optString("level", ScrapeEventLevel.Info.name))) }
}

enum class ScrapeTaskStatus { Running, Succeeded, Failed }
enum class ScrapeEventLevel { Info, Success, Warning, Error }
