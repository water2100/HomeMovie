package com.example.localmovielibrary.translate

import com.example.localmovielibrary.data.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

class BaiduTranslateClient(
    private val settingsRepository: AppSettingsRepository,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun translate(
        text: String,
        from: String = "auto",
        to: String = "zh"
    ): String = withContext(ioDispatcher) {
        val query = text.trim()
        if (query.isBlank()) return@withContext ""
        val appId = settingsRepository.getBaiduTranslateAppId()
        val secretKey = settingsRepository.getBaiduTranslateSecretKey()
        if (appId.isBlank() || secretKey.isBlank()) {
            error("请先在设置中配置百度翻译 App ID 和密钥")
        }
        val salt = Random.nextInt(100_000, 999_999).toString()
        val sign = "$appId$query$salt$secretKey".md5()
        val body = FormBody.Builder()
            .add("q", query)
            .add("from", from)
            .add("to", to)
            .add("appid", appId)
            .add("salt", salt)
            .add("sign", sign)
            .build()
        val request = Request.Builder()
            .url(BAIDU_TRANSLATE_URL)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("百度翻译请求失败：HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val errorCode = json.optString("error_code").takeIf { it.isNotBlank() }
            if (errorCode != null) {
                val message = json.optString("error_msg", "未知错误")
                error("百度翻译失败：$errorCode $message")
            }
            val results = json.optJSONArray("trans_result") ?: return@withContext ""
            buildString {
                for (index in 0 until results.length()) {
                    val dst = results.optJSONObject(index)?.optString("dst").orEmpty()
                    if (dst.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(dst)
                    }
                }
            }
        }
    }

    private fun String.md5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BAIDU_TRANSLATE_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    }
}
