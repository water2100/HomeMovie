package com.example.localmovielibrary.ui.logs

data class NumberScrapeLog(
    val number: String,
    val events: List<NumberScrapeLogEvent>
) {
    val status: NumberScrapeLogStatus = NumberScrapeLogStatus.from(events)

    fun copyText(): String = events.joinToString("\n") { event ->
        "[${event.timestamp}] ${event.message}"
    }
}

data class NumberScrapeLogEvent(
    val timestamp: String,
    val message: String
)

enum class NumberScrapeLogStatus {
    Success,
    Warning,
    Failed,
    Running;

    companion object {
        fun from(events: List<NumberScrapeLogEvent>): NumberScrapeLogStatus {
            val messages = events.map(NumberScrapeLogEvent::message)
            val last = messages.lastOrNull().orEmpty()
            return when {
                last.containsAny("失败", "错误", "异常") -> Failed
                messages.any {
                    it.containsAny("数据库已同步", "索引表已同步", "任务完成") ||
                        (it.contains("[刮削任务:") && it.contains("完成"))
                } -> Success
                messages.any { it.containsAny("警告", "跳过", "重试", "未刮削") } -> Warning
                else -> Running
            }
        }
    }
}

internal fun parseNumberScrapeLogs(log: String): List<NumberScrapeLog> {
    val builders = linkedMapOf<String, NumberScrapeLogBuilder>()
    log.lineSequence()
        .filter(String::isNotBlank)
        .toList()
        .asReversed()
        .forEach { line ->
            val event = line.toEvent() ?: return@forEach
            val dividerNumber = event.message.removePrefix(NUMBER_MARKER)
                .takeIf { event.message.startsWith(NUMBER_MARKER) }
                ?.trim()
            if (dividerNumber != null) {
                builders.getOrPut(dividerNumber) { NumberScrapeLogBuilder(dividerNumber) }
                return@forEach
            }

            val taggedNumber = NUMBER_TAG.find(event.message)?.groupValues?.get(1)
            val message = event.message.removePrefix("【番号=${taggedNumber.orEmpty()}】")
            val detectedNumber = taggedNumber ?: NUMBER_IN_MESSAGE.find(message)?.groupValues?.get(1)
            detectedNumber?.trim()?.takeIf(String::isNotBlank)?.let { number ->
                builders.getOrPut(number) { NumberScrapeLogBuilder(number) }
                    .events
                    .add(event.copy(message = message))
            }
        }
    return builders
        .values
        .toList()
        .asReversed()
        .map { builder -> NumberScrapeLog(builder.number, builder.events) }
        .filter { it.events.isNotEmpty() }
}

private fun String.toEvent(): NumberScrapeLogEvent? {
    val end = indexOf("] ")
    if (!startsWith("[") || end <= 1) return null
    return NumberScrapeLogEvent(
        timestamp = substring(1, end),
        message = substring(end + 2)
    )
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

private data class NumberScrapeLogBuilder(
    val number: String,
    val events: MutableList<NumberScrapeLogEvent> = mutableListOf()
)

private const val NUMBER_MARKER = "【番号分隔】"
private val NUMBER_TAG = Regex("^【番号=([^】]+)】")
private val NUMBER_IN_MESSAGE = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,12}-\\d{2,6})(?:-[A-Z0-9]+)?")
