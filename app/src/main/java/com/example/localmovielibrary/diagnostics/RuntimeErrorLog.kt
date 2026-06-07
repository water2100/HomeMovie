package com.example.localmovielibrary.diagnostics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuntimeErrorLog(context: Context) {
    private val appContext = context.applicationContext
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun append(event: String, details: Map<String, String?> = emptyMap(), error: Throwable? = null) {
        val text = buildString {
            append('[').append(timestamp()).append("] ").append(event).append('\n')
            details.forEach { (key, value) ->
                append("  ").append(key).append(": ").append(value.orEmpty()).append('\n')
            }
            if (error != null) {
                append("  errorType: ").append(error::class.java.name).append('\n')
                append("  errorMessage: ").append(error.message.orEmpty()).append('\n')
                append("  stack: ").append(error.stackTraceToString().lineSequence().take(24).joinToString("\\n")).append('\n')
            }
            append('\n')
        }
        writerScope.launch {
            mutex.withLock {
                val file = logFile()
                file.parentFile?.mkdirs()
                file.appendText(text, Charsets.UTF_8)
                trimIfNeeded(file)
            }
        }
    }

    fun filePath(): String = logFile().absolutePath

    private fun logFile(): File =
        File(appContext.filesDir, "diagnostics/runtime-errors.log")

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val text = file.readText(Charsets.UTF_8)
        val trimmed = text.takeLast(TRIM_TO_CHARS)
        file.writeText(trimmed, Charsets.UTF_8)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    private companion object {
        const val MAX_BYTES = 512 * 1024
        const val TRIM_TO_CHARS = 256 * 1024
    }
}
