package com.example.localmovielibrary.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScrapePipelineTest {
    @Test
    fun `stuck persisted task does not block unrelated issue movies`() = runBlocking {
        val movieStarted = CompletableDeferred<Unit>()
        val pipeline = launch {
            resumeScrapeTaskPipelines(
                hasCloudVideoTasks = true,
                startCloudVideoTasks = {},
                startMovieTasks = { movieStarted.complete(Unit) }
            )
        }

        val started = withTimeoutOrNull(200) {
            movieStarted.await()
            true
        } ?: false
        pipeline.join()

        assertTrue("一个卡住的持久化任务不应阻塞其他未刮削影片", started)
    }

    @Test
    fun `app restart starts persisted and unowned movie tasks without global waiting`() = runBlocking {
        val events = mutableListOf<String>()

        val job = launch {
            resumeScrapeTaskPipelines(
                hasCloudVideoTasks = true,
                startCloudVideoTasks = { events += "cloud-start" },
                startMovieTasks = { events += "movie-start" }
            )
        }

        yield()
        job.join()
        assertEquals(listOf("cloud-start", "movie-start"), events)
    }
}
