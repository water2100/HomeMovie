package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.data.local.DirectLinkDao
import com.example.localmovielibrary.data.local.DirectLinkEntity
import com.example.localmovielibrary.playback.DirectLinkExpiryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DirectLinkRepository(
    private val directLinkDao: DirectLinkDao,
    private val cloud115Client: Cloud115Client
) {
    suspend fun resolve(pickcode: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = nowSeconds()
        val cached = if (forceRefresh) null else directLinkDao.get(pickcode)
        if (cached != null && cached.url.isNotBlank() && cached.expiresAt > now) {
            return@withContext cached.url
        }

        val directUrl = cloud115Client.fetchDirectUrl(pickcode)
        directLinkDao.upsert(
            DirectLinkEntity(
                pickcode = pickcode,
                url = directUrl,
                expiresAt = DirectLinkExpiryParser.parseExpiresAt(directUrl, now),
                updatedAt = now
            )
        )
        directUrl
    }

    suspend fun invalidate(pickcode: String) = withContext(Dispatchers.IO) {
        directLinkDao.delete(pickcode)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
