package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NetworkProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("https://www.google.com/generate_204")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        }.getOrDefault(false)
    }
}
