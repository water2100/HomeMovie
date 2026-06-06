package com.example.localmovielibrary.cloud115

import android.content.Context
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.playback.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Cloud115LoginApp(
    val app: String,
    val description: String
)

object Cloud115LoginApps {
    val all: List<Cloud115LoginApp> = listOf(
        Cloud115LoginApp("web", "\u0031\u0031\u0035\u751f\u6d3b_\u7f51\u9875\u7aef"),
        Cloud115LoginApp("ios", "\u0031\u0031\u0035\u751f\u6d3b_\u82f9\u679c\u7aef"),
        Cloud115LoginApp("115ios", "\u0031\u0031\u0035_\u82f9\u679c\u7aef"),
        Cloud115LoginApp("bandroid", "\u672a\u77e5: android"),
        Cloud115LoginApp("115ipad", "\u0031\u0031\u0035_\u82f9\u679c\u5e73\u677f\u7aef"),
        Cloud115LoginApp("tv", "\u0031\u0031\u0035\u751f\u6d3b_\u5b89\u5353\u7535\u89c6\u7aef"),
        Cloud115LoginApp("apple_tv", "\u0031\u0031\u0035\u751f\u6d3b_\u82f9\u679c\u7535\u89c6\u7aef"),
        Cloud115LoginApp("qios", "\u0031\u0031\u0035\u7ba1\u7406_\u82f9\u679c\u7aef"),
        Cloud115LoginApp("os_windows", "\u0031\u0031\u0035\u751f\u6d3b_Windows\u7aef"),
        Cloud115LoginApp("os_linux", "\u0031\u0031\u0035\u751f\u6d3b_Linux\u7aef"),
        Cloud115LoginApp("wechatmini", "\u0031\u0031\u0035\u751f\u6d3b_\u5fae\u4fe1\u5c0f\u7a0b\u5e8f\u7aef"),
        Cloud115LoginApp("alipaymini", "\u0031\u0031\u0035\u751f\u6d3b_\u652f\u4ed8\u5b9d\u5c0f\u7a0b\u5e8f"),
        Cloud115LoginApp("harmony", "\u0031\u0031\u0035_\u9e3f\u8499\u7aef")
    )

    val default: Cloud115LoginApp = all.first { it.app == "ios" }

    fun find(app: String?): Cloud115LoginApp =
        all.firstOrNull { it.app == app } ?: default
}

data class Cloud115QrToken(
    val uid: String,
    val time: String,
    val sign: String,
    val qrImageUrl: String
)

enum class Cloud115QrLoginStatus {
    Waiting,
    Scanned,
    Confirmed,
    Expired,
    Canceled
}

data class Cloud115QrLoginResult(
    val userId: String,
    val app: String,
    val cookies: String,
    val fileName: String,
    val filePath: String
)

data class SavedCloud115Account(
    val fileName: String,
    val filePath: String,
    val displayName: String,
    val cookies: String
)

class Cloud115QrLoginClient(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext

    suspend fun listSavedAccounts(): List<SavedCloud115Account> = withContext(Dispatchers.IO) {
        importBundledCookieFilesIfNeeded()
        cookieDirectory()
            .listFiles { file -> file.isFile && file.name.startsWith(COOKIE_FILE_PREFIX) && file.name.endsWith(COOKIE_FILE_SUFFIX) }
            ?.mapNotNull { file ->
                val cookies = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SavedCloud115Account(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    displayName = file.name.toSavedAccountDisplayName(),
                    cookies = cookies
                )
            }
            ?.sortedBy { it.displayName.lowercase() }
            .orEmpty()
    }

    suspend fun applySavedAccount(fileName: String): SavedCloud115Account = withContext(Dispatchers.IO) {
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val file = File(cookieDirectory(), safeFileName)
        if (!file.isFile) error("未找到账号 Cookie：$safeFileName")
        val cookies = file.readText(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
            ?: error("账号 Cookie 为空：$safeFileName")
        settingsRepository.saveCookies(cookies)
        SavedCloud115Account(
            fileName = file.name,
            filePath = file.absolutePath,
            displayName = file.name.toSavedAccountDisplayName(),
            cookies = cookies
        )
    }
    suspend fun deleteSavedAccount(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val file = File(cookieDirectory(), safeFileName)
        if (!file.isFile) return@withContext false
        val cookies = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        val deleted = file.delete()
        if (deleted && cookies.isNotBlank() && settingsRepository.getCookies() == cookies) {
            settingsRepository.saveCookies("")
        }
        deleted
    }

    suspend fun requestToken(): Cloud115QrToken = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("115 二维码获取失败：HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            val data = JSONObject(raw).optJSONObject("data") ?: error("115 二维码响应为空")
            val uid = data.optString("uid").takeIf { it.isNotBlank() } ?: error("115 二维码响应缺少 uid")
            val time = data.optString("time").takeIf { it.isNotBlank() } ?: error("115 二维码响应缺少 time")
            val sign = data.optString("sign").takeIf { it.isNotBlank() } ?: error("115 二维码响应缺少 sign")
            val qrImageUrl = "$QR_IMAGE_URL?uid=$uid"
            Cloud115QrToken(uid = uid, time = time, sign = sign, qrImageUrl = qrImageUrl)
        }
    }

    suspend fun checkStatus(token: Cloud115QrToken): Cloud115QrLoginStatus = withContext(Dispatchers.IO) {
        val firstError = runCatching { checkStatus(STATUS_URL_PRIMARY, token) }
        firstError.getOrNull() ?: checkStatus(STATUS_URL_FALLBACK, token)
    }

    suspend fun login(token: Cloud115QrToken, loginApp: Cloud115LoginApp): Cloud115QrLoginResult =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("account", token.uid)
                .build()
            val request = Request.Builder()
                .url("https://passportapi.115.com/app/1.0/${loginApp.app}/1.0/login/qrcode/")
                .post(body)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("115 登录失败：HTTP ${response.code}")
                val raw = response.body?.string().orEmpty()
                val json = JSONObject(raw)
                if (!json.isSuccessState()) {
                    error(json.optString("message").takeIf { it.isNotBlank() } ?: "115 登录失败")
                }
                val data = json.optJSONObject("data") ?: error("115 登录响应为空")
                val userId = data.optString("user_id").takeIf { it.isNotBlank() } ?: token.uid
                val cookieObject = data.optJSONObject("cookie") ?: error("115 登录响应缺少 Cookie")
                val cookies = normalizeCookies(cookieObject)
                val fileName = "$COOKIE_FILE_PREFIX${userId}_${loginApp.app}$COOKIE_FILE_SUFFIX"
                val file = File(cookieDirectory(), fileName)
                file.writeText(cookies, Charsets.UTF_8)
                settingsRepository.saveCookies(cookies)
                settingsRepository.saveCloud115LoginApp(loginApp.app)
                Cloud115QrLoginResult(
                    userId = userId,
                    app = loginApp.app,
                    cookies = cookies,
                    fileName = fileName,
                    filePath = file.absolutePath
                )
            }
        }

    private fun checkStatus(url: String, token: Cloud115QrToken): Cloud115QrLoginStatus {
        val statusUrl = url.toHttpUrl().newBuilder()
            .addQueryParameter("uid", token.uid)
            .addQueryParameter("time", token.time)
            .addQueryParameter("sign", token.sign)
            .build()
        val request = Request.Builder()
            .url(statusUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("115 二维码状态获取失败：HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            val data = json.optJSONObject("data") ?: json
            val numericStatus = data.optInt("status", Int.MIN_VALUE)
            val statusText = data.optString("status").lowercase()
            if (numericStatus == Int.MIN_VALUE && statusText.isBlank()) {
                error("115 二维码状态响应缺少 status")
            }
            return when (numericStatus) {
                0 -> Cloud115QrLoginStatus.Waiting
                1 -> Cloud115QrLoginStatus.Scanned
                2 -> Cloud115QrLoginStatus.Confirmed
                -1 -> Cloud115QrLoginStatus.Expired
                -2 -> Cloud115QrLoginStatus.Canceled
                else -> {
                    when {
                        statusText.contains("scan") -> Cloud115QrLoginStatus.Scanned
                        statusText.contains("login") || statusText.contains("confirm") -> Cloud115QrLoginStatus.Confirmed
                        statusText.contains("expire") -> Cloud115QrLoginStatus.Expired
                        statusText.contains("cancel") -> Cloud115QrLoginStatus.Canceled
                        else -> Cloud115QrLoginStatus.Waiting
                    }
                }
            }
        }
    }

    private fun normalizeCookies(cookieObject: JSONObject): String {
        val ordered = listOf("UID", "CID", "SEID", "KID")
        val values = ordered.mapNotNull { key ->
            cookieObject.optString(key).takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }
        if (values.isEmpty()) error("115 登录响应 Cookie 为空")
        return values.joinToString("; ")
    }

    private fun JSONObject.isSuccessState(): Boolean {
        if (optBoolean("state", false)) return true
        return optInt("state", 0) == 1
    }

    private fun cookieDirectory(): File =
        File(appContext.filesDir, COOKIE_DIR).apply { mkdirs() }

    private fun importBundledCookieFilesIfNeeded() {
        val assetFiles = appContext.assets.list("").orEmpty()
        assetFiles
            .filter { it == LEGACY_ASSET_COOKIE_FILE || (it.startsWith(COOKIE_FILE_PREFIX) && it.endsWith(COOKIE_FILE_SUFFIX)) }
            .forEach { assetName ->
                val cookies = runCatching {
                    appContext.assets.open(assetName).bufferedReader().use { it.readText() }.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                val fileName = when {
                    assetName == LEGACY_ASSET_COOKIE_FILE -> {
                        val userId = extractUserId(cookies) ?: "unknown"
                        "$COOKIE_FILE_PREFIX${userId}_web$COOKIE_FILE_SUFFIX"
                    }

                    assetName.startsWith(COOKIE_FILE_PREFIX) -> assetName
                    else -> return@forEach
                }
                val target = File(cookieDirectory(), fileName)
                if (!target.exists()) {
                    target.writeText(cookies, Charsets.UTF_8)
                }
                if (settingsRepository.getCookies().isBlank()) {
                    settingsRepository.saveCookies(cookies)
                }
            }
    }

    private fun String.toSavedAccountDisplayName(): String {
        val baseName = removePrefix(COOKIE_FILE_PREFIX)
            .removeSuffix(COOKIE_FILE_SUFFIX)
            .ifBlank { this }
        val app = Cloud115LoginApps.all
            .sortedByDescending { it.app.length }
            .firstOrNull { baseName.endsWith("_${it.app}") }
        if (app != null) {
            val accountName = baseName.removeSuffix("_${app.app}").ifBlank { baseName }
            return "${accountName}_${app.description}"
        }
        return baseName
    }

    private fun extractUserId(cookies: String): String? {
        val uidValue = cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("UID=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return uidValue.substringBefore('_').takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TOKEN_URL = "https://qrcodeapi.115.com/api/1.0/web/1.0/token/"
        const val STATUS_URL_PRIMARY = "https://qrcodeapi.115.com/get/status/"
        const val STATUS_URL_FALLBACK = "https://qrcodeapi.115.com/api/1.0/web/1.0/status/"
        const val QR_IMAGE_URL = "https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode"
        const val COOKIE_DIR = "115cookies"
        const val COOKIE_FILE_PREFIX = "115cookie_"
        const val COOKIE_FILE_SUFFIX = ".txt"
        const val LEGACY_ASSET_COOKIE_FILE = "115-cookies.txt"
    }
}

