package com.example.localmovielibrary.ui.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberScrapeLogTest {
    @Test
    fun `groups reverse stored lines by movie number and preserves chronological events`() {
        val logs = parseNumberScrapeLogs(
            """
            [10:00:03] 【番号=ABC-123】影片表已同步
            [10:00:02] 【番号=ABC-123】写入 NFO
            [10:00:01] 【番号分隔】ABC-123
            [09:59:02] 【番号=OLD-001】写入 NFO
            [09:59:01] 【番号分隔】OLD-001
            """.trimIndent()
        )

        assertEquals(listOf("ABC-123", "OLD-001"), logs.map(NumberScrapeLog::number))
        assertEquals(listOf("10:00:02", "10:00:03"), logs.first().events.map(NumberScrapeLogEvent::timestamp))
        assertEquals(NumberScrapeLogStatus.Success, logs.first().status)
    }

    @Test
    fun `keeps terminal events with their number when concurrent logs interleave`() {
        val logs = parseNumberScrapeLogs(
            """
            [10:00:04] 【番号=BBB-222】影片表已同步
            [10:00:03] 【番号=AAA-111】影片表已同步
            [10:00:02] 【番号分隔】BBB-222
            [10:00:01] 【番号分隔】AAA-111
            """.trimIndent()
        ).associateBy(NumberScrapeLog::number)

        assertEquals(NumberScrapeLogStatus.Success, logs.getValue("AAA-111").status)
        assertEquals(NumberScrapeLogStatus.Success, logs.getValue("BBB-222").status)
    }

    @Test
    fun `copies a number log in user readable timestamp format`() {
        val log = NumberScrapeLog(
            number = "ABC-123",
            events = listOf(NumberScrapeLogEvent("10:00:00", "开始"))
        )

        assertTrue(log.copyText().contains("[10:00:00] 开始"))
    }
}
