package com.example.localmovielibrary.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieScrapeTaskRunnerConcurrencyTest {
    @Test
    fun `hung scrape request is failed so later movies can continue`() = runBlocking {
        val completed = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        val batchFinished = withTimeoutOrNull(300) {
            runMovieBatchWorkers(listOf(1, 2), concurrency = 1) { _, movie ->
                processMovieScrapeTask(
                    markRunning = {},
                    runScrape = {
                        if (movie == 1) awaitCancellation()
                        completed += movie
                    },
                    markPending = {},
                    markFailed = { failed += movie },
                    scrapeTimeoutMillis = 50
                )
            }
            true
        } ?: false

        assertTrue(batchFinished)
        assertEquals(listOf(1), failed)
        assertEquals(listOf(2), completed)
    }

    @Test
    fun `hung running status update does not block actual scrape forever`() = runBlocking {
        var scrapeStarted = false

        val outcome = withTimeoutOrNull(200) {
            processMovieScrapeTask(
                markRunning = { awaitCancellation() },
                runScrape = { scrapeStarted = true },
                markPending = {},
                markFailed = {},
                runningStatusTimeoutMillis = 50
            )
        }

        assertTrue(scrapeStarted)
        assertEquals(MovieScrapeTaskOutcome.Completed, outcome)
    }

    @Test
    fun `status transition failure is captured instead of killing batch silently`() = runBlocking {
        var failureCaptured = false

        val outcome = processMovieScrapeTask(
            markRunning = { error("database status update failed") },
            runScrape = { error("must not run") },
            markPending = {},
            markFailed = { failureCaptured = true }
        )

        assertEquals(MovieScrapeTaskOutcome.Failed, outcome)
        assertTrue(failureCaptured)
    }

    @Test
    fun `one pre-scrape failure does not stop remaining movies`() = runBlocking {
        val completed = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        runMovieBatchWorkers((1..3).toList(), concurrency = 1) { _, movie ->
            processMovieScrapeTask(
                markRunning = { if (movie == 1) error("first status update failed") },
                runScrape = { completed += movie },
                markPending = {},
                markFailed = { failed += movie }
            )
        }

        assertEquals(listOf(1), failed)
        assertEquals(listOf(2, 3), completed)
    }

    @Test
    fun `unfinished persisted task owns its movie while unrelated movies remain runnable`() {
        val selected = excludeOwnedScrapeTasks(
            tasks = listOf(101L, 102L, 103L),
            ownedMovieIds = setOf(102L),
            idOf = { it }
        )

        assertEquals(listOf(101L, 103L), selected)
    }

    @Test
    fun `configured workers scrape multiple movies concurrently`() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val job = launch {
            runMovieBatchWorkers((1..6).toList(), concurrency = 3) { _, _ ->
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, now) }
                release.await()
                active.decrementAndGet()
            }
        }

        withTimeout(1_000) {
            while (maximumActive.get() < 3) delay(5)
        }
        assertEquals(3, maximumActive.get())
        release.complete(Unit)
        job.join()
    }
}
