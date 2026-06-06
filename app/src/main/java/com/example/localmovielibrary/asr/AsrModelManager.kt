package com.example.localmovielibrary.asr

import android.content.Context
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AsrModelOption(
    val id: String,
    val label: String,
    val description: String
)

data class AsrModelStatus(
    val modelId: String,
    val modelDir: File,
    val modelFile: File,
    val tokensFile: File,
    val isReady: Boolean,
    val sizeBytes: Long
)

class AsrModelManager(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext

    fun availableModels(): List<AsrModelOption> = AVAILABLE_MODELS

    fun currentStatus(): AsrModelStatus = statusFor(settingsRepository.getAsrModelId())

    fun statusFor(modelId: String): AsrModelStatus {
        val dir = File(File(appContext.filesDir, MODEL_ROOT_DIR), modelId)
        val modelFile = File(dir, MODEL_FILE)
        val tokensFile = File(dir, TOKENS_FILE)
        return AsrModelStatus(
            modelId = modelId,
            modelDir = dir,
            modelFile = modelFile,
            tokensFile = tokensFile,
            isReady = modelFile.isFile && modelFile.length() > 0L && tokensFile.isFile && tokensFile.length() > 0L,
            sizeBytes = listOf(modelFile, tokensFile).filter { it.exists() }.sumOf { it.length() }
        )
    }

    suspend fun downloadCurrentModel(onProgress: (Int, String) -> Unit): AsrModelStatus = withContext(Dispatchers.IO) {
        val modelId = settingsRepository.getAsrModelId()
        val baseUrl = settingsRepository.getAsrModelBaseUrl()
        val status = statusFor(modelId)
        status.modelDir.mkdirs()
        val files = listOf(MODEL_FILE, TOKENS_FILE)
        files.forEachIndexed { index, fileName ->
            val percentBase = index * 50
            onProgress(percentBase, "正在下载 $fileName")
            downloadFile(
                url = "$baseUrl/$fileName",
                target = File(status.modelDir, fileName),
                onProgress = { filePercent ->
                    onProgress(percentBase + filePercent / 2, "正在下载 $fileName")
                }
            )
        }
        onProgress(100, "模型下载完成")
        statusFor(modelId)
    }

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.download")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("模型下载失败：HTTP ${response.code}")
            val body = response.body ?: error("模型下载响应为空")
            val total = body.contentLength()
            var readTotal = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (total > 0) {
                            onProgress(((readTotal * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
        if (tmp.length() <= 0L) error("模型文件为空：${target.name}")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val MODEL_ROOT_DIR = "asr_models"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        private val AVAILABLE_MODELS = listOf(
            AsrModelOption(
                id = AppSettingsRepository.DEFAULT_ASR_MODEL_ID,
                label = "SenseVoice 多语言 int8",
                description = "支持中文、英文、日语、韩语、粤语，适合当前实时字幕流程。"
            )
        )
    }
}
