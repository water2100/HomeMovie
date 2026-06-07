package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.data.local.PlaybackProgressDao
import com.example.localmovielibrary.data.local.PlaybackProgressEntity
import com.example.localmovielibrary.data.local.PlaybackProgressListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class PlaybackProgressRepository(
    private val playbackProgressDao: PlaybackProgressDao
) {
    fun observeRecent(limit: Int): Flow<List<PlaybackProgressListItem>> =
        playbackProgressDao.observeRecentListItems(limit).distinctUntilChanged()

    suspend fun getResumePosition(mediaKey: String): Long {
        val progress = playbackProgressDao.get(mediaKey) ?: return 0L
        return progress.positionMs.takeIf { it >= MIN_RESUME_POSITION_MS } ?: 0L
    }

    suspend fun save(mediaKey: String, positionMs: Long, durationMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (isNearEnd(safePosition, safeDuration)) {
            playbackProgressDao.delete(mediaKey)
            return
        }
        playbackProgressDao.upsert(
            PlaybackProgressEntity(
                mediaKey = mediaKey,
                positionMs = safePosition,
                durationMs = safeDuration,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clear(mediaKey: String) {
        playbackProgressDao.delete(mediaKey)
    }

    private fun isNearEnd(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return durationMs - positionMs <= END_CLEAR_THRESHOLD_MS ||
            positionMs >= (durationMs * END_CLEAR_PROGRESS_FRACTION).toLong()
    }

    private companion object {
        const val MIN_RESUME_POSITION_MS = 5_000L
        const val END_CLEAR_THRESHOLD_MS = 60_000L
        const val END_CLEAR_PROGRESS_FRACTION = 0.95f
    }
}
