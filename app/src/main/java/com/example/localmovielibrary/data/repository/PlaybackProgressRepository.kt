package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.data.local.PlaybackProgressDao
import com.example.localmovielibrary.data.local.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

class PlaybackProgressRepository(
    private val playbackProgressDao: PlaybackProgressDao
) {
    fun observeAll(): Flow<List<PlaybackProgressEntity>> = playbackProgressDao.observeAll()

    suspend fun getResumePosition(mediaKey: String): Long {
        val progress = playbackProgressDao.get(mediaKey) ?: return 0L
        return progress.positionMs.takeIf { it >= MIN_RESUME_POSITION_MS } ?: 0L
    }

    suspend fun save(mediaKey: String, positionMs: Long, durationMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (safePosition < MIN_RESUME_POSITION_MS) {
            playbackProgressDao.delete(mediaKey)
            return
        }
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
