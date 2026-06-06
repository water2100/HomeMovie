package com.example.localmovielibrary.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

class ImageDownloadService(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val retryCountProvider: () -> Int,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun downloadImageBytes(url: String): ByteArray = withContext(ioDispatcher) {
        var lastError: Throwable? = null
        val retryCount = retryCountProvider().coerceAtLeast(1)
        repeat(retryCount) { attempt ->
            val request = buildRequest(url)
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("图片下载失败 HTTP ${response.code}: $url")
                    return@withContext response.body?.bytes() ?: error("图片响应为空：$url")
                }
            }.onFailure { error ->
                lastError = error
                logger("图片下载第 ${attempt + 1} 次失败：${error.message ?: error::class.java.simpleName}")
                if (attempt < retryCount - 1) {
                    val delayMillis = Random.nextLong(1_000L, 2_001L)
                    logger("等待 ${delayMillis}ms 后重试图片下载")
                    delay(delayMillis)
                }
            }
        }
        throw lastError ?: IllegalStateException("图片下载失败：$url")
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder().url(url)
        if ("awsimgsrc.dmm.co.jp" !in url) {
            builder
                .header("User-Agent", IMAGE_USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        return builder.build()
    }

    private companion object {
        const val IMAGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
