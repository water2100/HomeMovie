package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.local.CloudVideoTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class CloudVideoTaskRunner(
    private val taskRepository: CloudVideoTaskRepository,
    processor: CloudVideoAddProcessor,
    settingsRepository: AppSettingsRepository
) {
    private val queue = createCloudVideoTaskQueue(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        configuredWorkerCount = settingsRepository::getScrapeConcurrencyLimit,
        store = taskRepository,
        processor = processor::process
    )

    val isRunning: StateFlow<Boolean> = queue.isRunning

    suspend fun enqueue(item: Cloud115FileItem, forceDistinct: Boolean): CloudVideoTaskEntity =
        taskRepository.enqueue(item, forceDistinct)

    suspend fun enqueueAndStart(item: Cloud115FileItem, forceDistinct: Boolean): CloudVideoTaskEntity {
        val task = enqueue(item, forceDistinct)
        queue.start()
        return task
    }

    suspend fun awaitTerminal(pickcodes: Set<String>): Map<String, CloudVideoTaskEntity> =
        taskRepository.awaitTerminal(pickcodes)

    fun start() = queue.start()

    fun pause() = queue.pause()

}

internal fun <T> createCloudVideoTaskQueue(
    scope: CoroutineScope,
    configuredWorkerCount: () -> Int,
    store: PersistentWorkStore<T>,
    processor: suspend (T) -> Unit,
    idlePollMillis: Long = 500L
): PersistentWorkerQueue<T> = PersistentWorkerQueue(
    scope = scope,
    configuredWorkerCount = configuredWorkerCount,
    store = store,
    processor = processor,
    idlePollMillis = idlePollMillis
)
