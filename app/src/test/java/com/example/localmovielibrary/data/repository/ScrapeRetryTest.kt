package com.example.localmovielibrary.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrapeRetryTest {
    @Test
    fun `default three attempts can succeed on third request`() = runBlocking {
        val attempts = AtomicInteger(0)

        val result = retryScrapeRequest(
            attemptCount = 3,
            retryDelayMillis = 0
        ) {
            if (attempts.incrementAndGet() < 3) error("temporary failure")
            "success"
        }

        assertEquals("success", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `attempt count caps repeated failures`() {
        val attempts = AtomicInteger(0)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                retryScrapeRequest<Unit>(attemptCount = 3, retryDelayMillis = 0) {
                    attempts.incrementAndGet()
                    error("still failing")
                }
            }
        }

        assertEquals(3, attempts.get())
    }
}
