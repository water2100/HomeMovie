package com.example.localmovielibrary.translate

import com.example.localmovielibrary.data.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TranslationClient(
    private val settingsRepository: AppSettingsRepository,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val baiduClient = BaiduTranslateClient(settingsRepository, httpClient, ioDispatcher)

    suspend fun translate(
        text: String,
        from: String = "auto",
        to: String = "zh"
    ): String =
        when (settingsRepository.getTranslateProvider()) {
            TranslateProvider.Baidu -> baiduClient.translate(text, from, to)
            TranslateProvider.DeepSeek -> translateWithDeepSeek(text)
        }

    private suspend fun translateWithDeepSeek(text: String): String = withContext(ioDispatcher) {
        val query = text.trim()
        if (query.isBlank()) return@withContext ""
        val apiKey = settingsRepository.getDeepSeekApiKey()
        if (apiKey.isBlank()) {
            error("请先在设置中配置 DeepSeek API Key")
        }

        val endpoint = settingsRepository.getDeepSeekBaseUrl().trimEnd('/') + "/chat/completions"
        val model = settingsRepository.getDeepSeekModel().ifBlank { AppSettingsRepository.DEFAULT_DEEPSEEK_MODEL }
        val thinkingEnabled = settingsRepository.isDeepSeekThinkingEnabled()
        val messages = JSONArray()
        if (settingsRepository.isDeepSeekPromptEnabled()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", settingsRepository.getDeepSeekTranslatePrompt())
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", query)
        )
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", messages)
        if (thinkingEnabled) {
            payload.put("thinking", JSONObject().put("type", "enabled"))
        } else {
            payload.put("thinking", JSONObject().put("type", "disabled"))
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error("DeepSeek 翻译请求失败：HTTP ${response.code}${message.takeIf { it.isNotBlank() }?.let { "，$it" }.orEmpty()}")
            }
            val json = JSONObject(body)
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
