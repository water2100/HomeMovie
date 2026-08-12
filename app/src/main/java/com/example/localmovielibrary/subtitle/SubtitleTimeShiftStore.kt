package com.example.localmovielibrary.subtitle

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

class SubtitleTimeShiftStore(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, "shifted_subtitles")

    fun prepareSubtitle(subtitle: LocalSubtitleFile, offsetMs: Long): LocalSubtitleFile {
        val extension = subtitle.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in supportedExtensions) return subtitle
        val decoded = appContext.contentResolver.openInputStream(subtitle.uri)
            ?.use { input -> decodeSubtitleBytes(input.readBytes()) }
            ?: return subtitle
        val original = decoded.text
        val shifted = when (extension) {
            "srt" -> shiftSrt(original, offsetMs)
            "vtt" -> shiftVtt(original, offsetMs)
            "ass", "ssa" -> shiftAss(original, offsetMs)
            else -> original
        }
        if (decoded.isUtf8 && shifted == original) return subtitle
        cacheDirectory.mkdirs()
        val cacheName = (subtitle.uri.toString() + "|" + offsetMs).sha256() + "." + extension
        val file = File(cacheDirectory, cacheName)
        file.writeText(shifted, Charsets.UTF_8)
        return LocalSubtitleFile(name = subtitle.name, uri = Uri.fromFile(file))
    }

    private fun shiftSrt(content: String, offsetMs: Long): String =
        srtTimeRegex.replace(content) { match ->
            formatSrtTime(parseSrtTime(match.value) + offsetMs)
        }

    private fun shiftVtt(content: String, offsetMs: Long): String =
        vttTimeRegex.replace(content) { match ->
            formatVttTime(parseVttTime(match.value) + offsetMs, match.value.count { it == ':' } >= 2)
        }

    private fun shiftAss(content: String, offsetMs: Long): String =
        content.lineSequence()
            .joinToString("\n") { line ->
                if (!line.startsWith("Dialogue:", ignoreCase = true)) return@joinToString line
                val fields = line.removePrefix("Dialogue:").split(',', limit = 10).toMutableList()
                if (fields.size < 3) return@joinToString line
                fields[1] = formatAssTime(parseAssTime(fields[1].trim()) + offsetMs)
                fields[2] = formatAssTime(parseAssTime(fields[2].trim()) + offsetMs)
                "Dialogue:" + fields.joinToString(",")
            }

    private fun parseSrtTime(value: String): Long {
        val parts = value.split(':', ',')
        return (((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000) + parts[3].toLong()
    }

    private fun formatSrtTime(valueMs: Long): String {
        val totalMs = max(0L, valueMs)
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1_000) % 60
        val millis = totalMs % 1_000
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
    }

    private fun parseVttTime(value: String): Long {
        val normalized = value.replace(',', '.')
        val parts = normalized.split(':')
        val secondParts = parts.last().split('.')
        val seconds = secondParts[0].toLong()
        val millis = secondParts.getOrNull(1).orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return if (parts.size == 3) {
            (((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + seconds) * 1000) + millis
        } else {
            ((parts[0].toLong() * 60 + seconds) * 1000) + millis
        }
    }

    private fun formatVttTime(valueMs: Long, includeHours: Boolean): String {
        val totalMs = max(0L, valueMs)
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1_000) % 60
        val millis = totalMs % 1_000
        return if (includeHours || hours > 0) {
            "%02d:%02d:%02d.%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
        } else {
            "%02d:%02d.%03d".format(Locale.ROOT, minutes, seconds, millis)
        }
    }

    private fun parseAssTime(value: String): Long {
        val parts = value.split(':', '.')
        return (((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000) +
            (parts.getOrNull(3)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L) * 10L
    }

    private fun formatAssTime(valueMs: Long): String {
        val totalMs = max(0L, valueMs)
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1_000) % 60
        val centis = (totalMs % 1_000) / 10
        return "%d:%02d:%02d.%02d".format(Locale.ROOT, hours, minutes, seconds, centis)
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val supportedExtensions = setOf("srt", "vtt", "ass", "ssa")
        private val srtTimeRegex = Regex("""\d{2}:\d{2}:\d{2},\d{3}""")
        private val vttTimeRegex = Regex("""(?:\d{2}:)?\d{2}:\d{2}[\.,]\d{3}""")
    }
}
