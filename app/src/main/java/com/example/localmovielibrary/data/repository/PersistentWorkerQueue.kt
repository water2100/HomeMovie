package com.example.localmovielibrary.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal interface PersistentWorkStore<T> {
    suspend fun prepareForStart()
    suspend fun claimNext(): T?
    suspend fun markCompleted(work: T)
    suspend fun markFailed(work: T, reason: String)
    suspend fun release(work: T)
}

internal class PersistentWorkerQueue<T>(
    private val scope: CoroutineScope,
    private val configuredWorkerCount: () -> Int,
    private val store: PersistentWorkStore<T>,
    private val processor: suspend (T) -> Unit,
    private val idlePollMillis: Long = 500L
) {
    private val _isRunning = MutableStateFlow(false)
    private val lock = Any()
    private var controllerJob: Job? = null
    private var restartRequested = false
    private val activeWorkCount = AtomicInteger(0)

    constructor(
        scope: CoroutineScope,
        workerCount: Int,
        store: PersistentWorkStore<T>,
        processor: suspend (T) -> Unit,
        idlePollMillis: Long = 500L
    ) : this(
        scope = scope,
        configuredWorkerCount = { workerCount },
        store = store,
        processor = processor,
        idlePollMillis = idlePollMillis
    )

    val isRunning: StateFlow<Boolean> = _isRunning

    fun start() {
        synchronized(lock) {
            if (controllerJob?.isCompleted == false) {
                restartRequested = true
                return
            }
            val nextController = scope.launch(start = CoroutineStart.LAZY) {
                store.prepareForStart()
                _isRunning.value = true
                try {
                    val workerCount = configuredWorkerCount().coerceAtLeast(1)
                    val workers = List(workerCount) { launch { workLoop() } }
                    workers.forEach { it.join() }
                } finally {
                    _isRunning.value = false
                }
            }
            nextController.invokeOnCompletion {
                val shouldRestart = synchronized(lock) {
                    if (controllerJob === nextController) controllerJob = null
                    restartRequested.also { restartRequested = false }
                }
                if (shouldRestart) start()
            }
            controllerJob = nextController
            nextController.start()
        }
    }

    fun pause() {
        synchronized(lock) {
            restartRequested = false
            controllerJob?.cancel(CancellationException("任务队列已暂停"))
        }
    }

    private suspend fun workLoop() {
        var idlePolls = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val work = store.claimNext()
            if (work == null) {
                idlePolls += 1
                if (idlePolls >= MAX_IDLE_POLLS && activeWorkCount.get() == 0) return
                delay(idlePollMillis)
                continue
            }
            idlePolls = 0
            activeWorkCount.incrementAndGet()
            try {
                processor(work)
                kotlin.coroutines.coroutineContext.ensureActive()
                store.markCompleted(work)
            } catch (error: CancellationException) {
                withContext(NonCancellable) { store.release(work) }
                throw error
            } catch (error: Throwable) {
                store.markFailed(work, error.message ?: error::class.java.simpleName)
            } finally {
                activeWorkCount.decrementAndGet()
            }
        }
    }

    private companion object {
        const val MAX_IDLE_POLLS = 4
    }
}
