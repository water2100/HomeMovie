package com.example.localmovielibrary.cloud115

import com.example.localmovielibrary.playback.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Cloud115ApiClient(
    private val cookieProvider: Cloud115CookieProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) : Cloud115Client {
    override suspend fun listFiles(cid: Long): List<Cloud115FileItem> = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val request = Request.Builder()
            .url("$FILES_URL?aid=1&cid=$cid&o=user_ptime&asc=0&offset=0&show_dir=1&limit=1000&format=json")
            .get()
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 目录读取失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            val data = json.optJSONArray("data") ?: error("115 目录响应为空")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("n").takeIf { it.isNotBlank() } ?: continue
                    val fid = item.optString("fid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    val cidValue = item.optString("cid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    add(
                        Cloud115FileItem(
                            name = name,
                            cid = cidValue,
                            fid = fid,
                            pickcode = item.optString("pc").takeIf { it.isNotBlank() },
                            size = item.optString("s").toLongOrNull(),
                            modifiedAt = item.optTimestamp(
                                "t",
                                "user_ptime",
                                "ptime",
                                "pt",
                                "te",
                                "tu",
                                "tp",
                                "mtime",
                                "utime"
                            ),
                            isDirectory = fid == null
                        )
                    )
                }
            }
        }
    }

    override suspend fun fetchDirectUrl(pickcode: String): String = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val payload = JSONObject().put("pickcode", pickcode).toString()
        val encryptedPayload = P115Cipher.encrypt(payload)
        val body = FormBody.Builder()
            .add("data", encryptedPayload)
            .build()
        val request = Request.Builder()
            .url(DOWNLOAD_URL)
            .post(body)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 获取直链失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", false)) {
                error(json.optString("message", "115 获取直链失败"))
            }
            val encryptedData = json.optString("data")
            if (encryptedData.isBlank()) error("115 响应缺少直链数据")
            val data = JSONObject(P115Cipher.decrypt(encryptedData))
            extractDirectUrl(data) ?: error("115 响应中没有可播放直链")
        }
    }

    override suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 图片下载失败：HTTP ${response.code}")
            }
            response.body?.bytes() ?: error("115 图片响应为空")
        }
    }

    private fun extractDirectUrl(data: JSONObject): String? {
        data.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = data.optJSONObject(key) ?: continue
            val urlObject = item.optJSONObject("url")
            urlObject?.optString("url")?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
            item.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optTimestamp(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = optString(key).takeIf { it.isNotBlank() && it != "0" } ?: return@forEach
            value.toLongOrNull()?.let { raw ->
                return if (raw < 10_000_000_000L) raw * 1000L else raw
            }
        }
        return null
    }

    private companion object {
        const val FILES_URL = "https://webapi.115.com/files"
        const val DOWNLOAD_URL = "https://proapi.115.com/app/chrome/downurl"
    }
}
