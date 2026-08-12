package com.example.localmovielibrary.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentWorkerQueueTest {
    @Test
    fun `new app process recovers work left running by old process`() = runBlocking {
        val store = InMemoryPersistentStore(listOf(TestWork(11)))
        store.forceStatus(11, WorkStatus.Running)
        val processed = CompletableDeferred<Int>()
        val recreatedQueue = PersistentWorkerQueue(
            scope = this,
            workerCount = 1,
            store = store,
            processor = { processed.complete(it.id) },
            idlePollMillis = 10
        )

        recreatedQueue.start()

        assertEquals(11, withTimeout(1_000) { processed.await() })
        withTimeout(1_000) { while (store.statusOf(11) != WorkStatus.Completed) delay(5) }
        recreatedQueue.pause()
    }

    @Test
    fun `pause then start waits for canceled worker before retrying the same work`() = runBlocking {
        val store = InMemoryPersistentStore(listOf(TestWork(9)))
        val attempts = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val queue = PersistentWorkerQueue(
            scope = this,
            workerCount = 1,
            store = store,
            processor = {
                when (attempts.incrementAndGet()) {
                    1 -> {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                    }
                    2 -> secondStarted.complete(Unit)
                }
            },
            idlePollMillis = 10
        )

        queue.start()
        firstStarted.await()
        queue.pause()
        queue.start()

        delay(100)
        val attemptsBeforeCanceledWorkerExited = attempts.get()
        releaseFirst.complete(Unit)
        withTimeout(1_000) { secondStarted.await() }
        withTimeout(1_000) { while (store.statusOf(9) != WorkStatus.Completed) delay(5) }
        assertEquals("旧任务退出前不应重复领取同一影片", 1, attemptsBeforeCanceledWorkerExited)
        assertEquals(2, attempts.get())
        queue.pause()
    }

    @Test
    fun `queued work survives executor recreation`() = runBlocking {
        val store = InMemoryPersistentStore(listOf(TestWork(1)))
        val firstScope = CoroutineScope(SupervisorJob())
        val firstStarted = CompletableDeferred<Unit>()
        val firstQueue = PersistentWorkerQueue(
            scope = firstScope,
            workerCount = 1,
            store = store,
            processor = {
                firstStarted.complete(Unit)
                awaitCancellation()
            },
            idlePollMillis = 10
        )
        firstQueue.start()
        firstStarted.await()
        firstQueue.pause()
        withTimeout(1_000) { while (store.statusOf(1) != WorkStatus.Paused) delay(5) }
        firstScope.cancel()

        val resumed = CompletableDeferred<Int>()
        val recreatedQueue = PersistentWorkerQueue(
            scope = this,
            workerCount = 1,
            store = store,
            processor = { resumed.complete(it.id) },
            idlePollMillis = 10
        )
        recreatedQueue.start()

        assertEquals(1, withTimeout(1_000) { resumed.await() })
        withTimeout(1_000) { while (store.statusOf(1) != WorkStatus.Completed) delay(5) }
        recreatedQueue.pause()
    }

    @Test
    fun `worker limit bounds hundreds of persisted tasks`() = runBlocking {
        val store = InMemoryPersistentStore((0 until 357).map(::TestWork))
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val queue = PersistentWorkerQueue(
            scope = this,
            workerCount = 4,
            store = store,
            processor = {
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, now) }
                release.await()
                active.decrementAndGet()
                completed.incrementAndGet()
            },
            idlePollMillis = 10
        )

        queue.start()
        withTimeout(1_000) { while (maximumActive.get() < 4) delay(5) }
        assertEquals(4, maximumActive.get())
        release.complete(Unit)
        withTimeout(3_000) { while (completed.get() < 357) delay(5) }
        assertTrue(store.allCompleted())
        queue.pause()
    }

    @Test
    fun `configured cloud video workers process three persisted additions at the same time`() = runBlocking {
        val store = InMemoryPersistentStore(listOf(TestWork(1)))
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queue = createCloudVideoTaskQueue(
            scope = this,
            configuredWorkerCount = { 3 },
            store = store,
            processor = { work ->
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, now) }
                if (work.id == 1) firstStarted.complete(Unit)
                release.await()
                active.decrementAndGet()
            },
            idlePollMillis = 10
        )

        queue.start()
        firstStarted.await()
        delay(100)
        store.enqueue(TestWork(2))
        store.enqueue(TestWork(3))
        queue.start()

        withTimeout(1_000) { while (maximumActive.get() < 3) delay(5) }
        assertEquals("并发设置为 3 时，队列中应同时有 3 个处理中任务", 3, maximumActive.get())
        release.complete(Unit)
        withTimeout(1_000) { while (!store.allCompleted()) delay(5) }
        queue.pause()
    }

    @Test
    fun `failed work is not marked completed and retries on next start`() = runBlocking {
        val store = InMemoryPersistentStore(listOf(TestWork(7)))
        val attempts = AtomicInteger(0)
        val firstQueue = PersistentWorkerQueue(
            scope = this,
            workerCount = 1,
            store = store,
            processor = {
                attempts.incrementAndGet()
                error("network unavailable")
            },
            idlePollMillis = 10
        )
        firstQueue.start()
        withTimeout(1_000) { while (store.statusOf(7) != WorkStatus.Failed) delay(5) }
        delay(80)
        assertEquals(1, attempts.get())
        firstQueue.pause()

        val secondQueue = PersistentWorkerQueue(
            scope = this,
            workerCount = 1,
            store = store,
            processor = { attempts.incrementAndGet() },
            idlePollMillis = 10
        )
        secondQueue.start()
        withTimeout(1_000) { while (store.statusOf(7) != WorkStatus.Completed) delay(5) }
        assertEquals(2, attempts.get())
        secondQueue.pause()
    }
}

private data class TestWork(val id: Int)

private enum class WorkStatus { Pending, Running, Paused, Completed, Failed }

private class InMemoryPersistentStore(initial: List<TestWork>) : PersistentWorkStore<TestWork> {
    private val work = initial.associateWith { WorkStatus.Pending }.toMutableMap()

    override suspend fun prepareForStart() = synchronized(work) {
        work.replaceAll { _, status ->
            if (status == WorkStatus.Paused || status == WorkStatus.Running || status == WorkStatus.Failed) {
                WorkStatus.Pending
            } else {
                status
            }
        }
    }

    override suspend fun claimNext(): TestWork? = synchronized(work) {
        work.entries.firstOrNull { it.value == WorkStatus.Pending }?.also {
            it.setValue(WorkStatus.Running)
        }?.key
    }

    override suspend fun markCompleted(work: TestWork) = set(work, WorkStatus.Completed)

    override suspend fun markFailed(work: TestWork, reason: String) = set(work, WorkStatus.Failed)

    override suspend fun release(work: TestWork) = set(work, WorkStatus.Paused)

    fun statusOf(id: Int): WorkStatus? = synchronized(work) {
        work.entries.firstOrNull { it.key.id == id }?.value
    }

    fun allCompleted(): Boolean = synchronized(work) { work.values.all { it == WorkStatus.Completed } }

    fun forceStatus(id: Int, status: WorkStatus) = synchronized(work) {
        work.keys.firstOrNull { it.id == id }?.let { work[it] = status }
    }

    fun enqueue(item: TestWork) = synchronized(work) {
        work[item] = WorkStatus.Pending
    }

    private fun set(item: TestWork, status: WorkStatus) = synchronized(work) {
        work[item] = status
    }
}
