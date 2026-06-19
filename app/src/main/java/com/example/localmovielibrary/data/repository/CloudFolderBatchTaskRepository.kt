package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskDao
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrary.data.local.CloudFolderBatchTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

class CloudFolderBatchTaskRepository(
    private val dao: CloudFolderBatchTaskDao
) {
    fun observeTasks(): Flow<List<CloudFolderBatchTaskEntity>> =
        dao.observeAll().distinctUntilChanged()

    fun observeUnfinishedTaskCount(): Flow<Int> =
        dao.observeTaskCount(UNFINISHED_STATUSES).distinctUntilChanged()

    suspend fun enqueueFolder(folder: Cloud115FileItem): CloudFolderBatchTaskEntity = withContext(Dispatchers.IO) {
        val cid = folder.cid ?: error("这个文件夹没有 CID，无法批量添加")
        val now = System.currentTimeMillis()
        val existing = dao.getUnfinishedByFolderCid(cid)
        if (existing != null) {
            val nextStatus = if (existing.status == CloudFolderBatchTaskStatus.Failed.name) {
                CloudFolderBatchTaskStatus.Pending.name
            } else {
                existing.status
            }
            val updated = existing.copy(
                folderName = folder.name,
                status = nextStatus,
                failureMessage = if (nextStatus == CloudFolderBatchTaskStatus.Pending.name) null else existing.failureMessage,
                updatedAt = now
            )
            dao.upsert(updated)
            return@withContext updated
        }
        val id = dao.insert(
            CloudFolderBatchTaskEntity(
                folderCid = cid,
                folderName = folder.name,
                createdAt = now,
                updatedAt = now
            )
        )
        dao.get(id) ?: error("文件夹任务创建失败")
    }

    suspend fun nextRunnableTask(): CloudFolderBatchTaskEntity? = withContext(Dispatchers.IO) {
        dao.nextRunnable(RUNNABLE_STATUSES)
    }

    suspend fun resetForRun(taskId: Long): CloudFolderBatchTaskEntity? = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            status = CloudFolderBatchTaskStatus.Running.name,
            currentPath = current.folderName,
            currentFileName = null,
            queuedVideos = 0,
            processedVideos = 0,
            addedVideos = 0,
            scrapeFailedVideos = 0,
            skippedVideos = 0,
            failedVideos = 0,
            failedFolders = 0,
            failureMessage = null,
            updatedAt = now
        )
        dao.upsert(updated)
        updated
    }

    suspend fun updateProgress(taskId: Long, progress: CloudFolderBatchTaskProgress) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                currentPath = progress.currentPath,
                currentFileName = progress.currentFileName,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = progress.failureMessage,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markCompleted(taskId: Long, progress: CloudFolderBatchTaskProgress, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Completed.name,
                currentPath = progress.currentPath,
                currentFileName = null,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markPaused(taskId: Long, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Paused.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markFailed(taskId: Long, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Failed.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearCompletedTasks(): Int = withContext(Dispatchers.IO) {
        dao.deleteCompleted()
    }

    suspend fun clearUnfinishedTasks(): Int = withContext(Dispatchers.IO) {
        dao.deleteByStatuses(UNFINISHED_STATUSES)
    }

    companion object {
        val UNFINISHED_STATUSES = listOf(
            CloudFolderBatchTaskStatus.Pending.name,
            CloudFolderBatchTaskStatus.Running.name,
            CloudFolderBatchTaskStatus.Paused.name,
            CloudFolderBatchTaskStatus.Failed.name
        )

        val RUNNABLE_STATUSES = UNFINISHED_STATUSES
    }
}

data class CloudFolderBatchTaskProgress(
    val currentPath: String?,
    val currentFileName: String?,
    val queuedVideos: Int,
    val processedVideos: Int,
    val addedVideos: Int,
    val scrapeFailedVideos: Int,
    val skippedVideos: Int,
    val failedVideos: Int,
    val failedFolders: Int,
    val failureMessage: String?
)
