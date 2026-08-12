package com.example.localmovielibrary.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizedScrapeFinalizerTest {
    @Test
    fun `file write success is not task completion when database refresh cannot return a movie`() = runBlocking {
        var taskCompleted = false
        var failureReason: String? = null

        val failure = runCatching {
            finalizeOrganizedScrape(
                refreshMovie = { null as String? },
                markCompleted = { taskCompleted = true },
                markFailed = { failureReason = it },
                missingMovieMessage = "整理后的 STRM 未同步到数据库"
            )
        }.exceptionOrNull()

        assertFalse(taskCompleted)
        assertEquals("整理后的 STRM 未同步到数据库", failure?.message)
        assertEquals("整理后的 STRM 未同步到数据库", failureReason)
    }

    @Test
    fun `task completes only after database refresh returns the organized movie`() = runBlocking {
        val calls = mutableListOf<String>()

        val movie = finalizeOrganizedScrape(
            refreshMovie = {
                calls += "refresh-database"
                "organized-movie"
            },
            markCompleted = {
                calls += "complete-task:$it"
            },
            markFailed = { calls += "fail-task:$it" },
            missingMovieMessage = "unused"
        )

        assertEquals("organized-movie", movie)
        assertEquals(listOf("refresh-database", "complete-task:organized-movie"), calls)
        assertTrue(calls.indexOf("refresh-database") < calls.indexOf("complete-task:organized-movie"))
    }
}
