package com.example.localmovielibrary.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val sizeBytes: Long? = null,
    val force: Boolean = false,
    val notes: List<String> = emptyList()
)

data class AppUpdateCheckResult(
    val latest: AppUpdateInfo,
    val hasUpdate: Boolean
)

class AppUpdateRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val updateDirectory: File = File(appContext.cacheDir, "updates")
    private val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    @Volatile
    private var cachedCheckResult: AppUpdateCheckResult? = null

    val currentVersionCode: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    val currentVersionName: String = packageInfo.versionName.orEmpty()
    val lastCheckResult: AppUpdateCheckResult?
        get() = cachedCheckResult
    val updateDirectoryPath: String
        get() = updateDirectory.absolutePath

    suspend fun checkForUpdate(manifestUrl: String = settingsRepository.getUpdateManifestUrl()): AppUpdateCheckResult =
        withContext(Dispatchers.IO) {
            val normalizedUrl = manifestUrl.trim()
            if (normalizedUrl.isBlank()) error("请先填写版本信息地址")
            val request = Request.Builder()
                .url(updateRequestUrl(normalizedUrl))
                .header("User-Agent", "HomeMovie/$currentVersionName")
                .build()
            val body = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("检查更新失败：HTTP ${response.code}")
                if (text.isBlank()) error("版本信息为空")
                text
            }
            val latest = parseUpdateInfo(body, normalizedUrl)
            AppUpdateCheckResult(
                latest = latest,
                hasUpdate = latest.versionCode > currentVersionCode
            ).also { cachedCheckResult = it }
        }

    suspend fun downloadAndInstall(
        update: AppUpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkFile = downloadApk(update, onProgress)
        installApk(apkFile)
        apkFile
    }

    fun installDownloadedApk(): Boolean {
        val apkFile = latestDownloadedApk() ?: return false
        installApk(apkFile)
        return true
    }

    fun hasDownloadedApk(): Boolean = latestDownloadedApk() != null

    fun latestDownloadedApkPath(): String =
        latestDownloadedApk()?.absolutePath.orEmpty()

    suspend fun cleanupInstalledUpdateApksIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!settingsRepository.isUpdateAutoDeleteInstalledApkEnabled()) return@withContext false
        val pendingVersionCode = settingsRepository.getPendingUpdateInstallVersionCode()
        val pendingPath = settingsRepository.getPendingUpdateInstallApkPath()
        if (pendingVersionCode <= 0 || pendingVersionCode > currentVersionCode) return@withContext false
        var deleted = false
        updateDirectory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    (file.extension.equals("apk", ignoreCase = true) || file.name.endsWith(".download", ignoreCase = true))
            }
            ?.forEach { file ->
                deleted = file.delete() || deleted
            }
        if (pendingPath.isNotBlank()) {
            File(pendingPath).takeIf { it.exists() }?.let { file ->
                deleted = file.delete() || deleted
            }
        }
        settingsRepository.clearPendingUpdateInstallApk()
        deleted
    }

    private fun parseUpdateInfo(jsonText: String, manifestUrl: String): AppUpdateInfo {
        val json = JSONObject(jsonText)
        val versionCode = json.optInt("versionCode", -1)
        if (versionCode < 0) error("版本信息缺少 versionCode")
        val versionName = json.optString("versionName").ifBlank { versionCode.toString() }
        val rawApkUrl = json.optString("apkUrl").ifBlank { error("版本信息缺少 apkUrl") }
        val apkUrl = resolveUrl(manifestUrl, rawApkUrl)
        val notes = when (val rawNotes = json.opt("notes")) {
            is JSONArray -> buildList {
                for (index in 0 until rawNotes.length()) {
                    rawNotes.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            is String -> rawNotes.lines().map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = json.optString("sha256").trim(),
            sizeBytes = json.optLong("size", -1L).takeIf { it > 0L },
            force = json.optBoolean("force", false),
            notes = notes
        )
    }

    private fun resolveUrl(manifestUrl: String, value: String): String {
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        return manifestUrl.toHttpUrl().resolve(value)?.toString() ?: value
    }

    private fun downloadApk(update: AppUpdateInfo, onProgress: (Int) -> Unit): File {
        updateDirectory.mkdirs()
        val fileName = "HomeMovie-v${update.versionName}.apk".sanitizeFileName()
        val finalFile = File(updateDirectory, fileName)
        val tempFile = File(updateDirectory, "$fileName.download")
        val request = Request.Builder()
            .url(updateRequestUrl(update.apkUrl))
            .header("User-Agent", "HomeMovie/$currentVersionName")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载更新失败：HTTP ${response.code}")
            val body = response.body ?: error("下载更新失败：响应为空")
            val total = body.contentLength().takeIf { it > 0L } ?: update.sizeBytes ?: -1L
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var nextProgress = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            val progress = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (progress >= nextProgress) {
                                onProgress(progress)
                                nextProgress = progress + 3
                            }
                        }
                    }
                }
            }
        }
        if (update.sha256.isNotBlank()) {
            val actual = tempFile.sha256()
            if (!actual.equals(update.sha256, ignoreCase = true)) {
                tempFile.delete()
                error("APK 校验失败，文件可能已经损坏")
            }
        }
        finalFile.delete()
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        settingsRepository.savePendingUpdateInstallApk(update.versionCode, finalFile.absolutePath)
        return finalFile
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) error("APK 文件不存在，请重新下载")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            openUnknownAppInstallSettings()
            error("请开启“允许安装未知应用”后，再点击安装已下载 APK")
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            appContext.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("没有找到可用的 APK 安装器", error)
        }
    }

    private fun openUnknownAppInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun updateRequestUrl(url: String): String {
        val normalizedUrl = url.trim()
        val proxyBaseUrl = settingsRepository.getUpdateProxyBaseUrl()
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
            ?: return normalizedUrl
        if (!normalizedUrl.isGithubUrl() || normalizedUrl.startsWith("$proxyBaseUrl/", ignoreCase = true)) {
            return normalizedUrl
        }
        return "$proxyBaseUrl/$normalizedUrl"
    }

    private fun latestDownloadedApk(): File? =
        updateDirectory
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun String.isGithubUrl(): Boolean =
        startsWith("https://github.com/", ignoreCase = true) ||
            startsWith("http://github.com/", ignoreCase = true)

    private companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
