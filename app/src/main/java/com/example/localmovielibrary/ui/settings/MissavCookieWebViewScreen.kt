package com.example.localmovielibrary.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.localmovielibrary.scraper.MissavScrapeLanguage
import com.example.localmovielibrary.scraper.MissavScraper
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MissavCookieWebViewScreen(
    number: String,
    scrapeLanguage: MissavScrapeLanguage = MissavScrapeLanguage.Default,
    onBack: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    var saved by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf("请在此页面打开一次 MissAV。获取到 Cookie 后会自动保存，后续刮削会在后台继续。")
    }
    val missavUrl = remember(number, scrapeLanguage) { scrapeLanguage.movieUrl(number) }
    BackHandler { onBack() }

    fun trySaveCookie(view: WebView?) {
        if (saved || view == null) return
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
            val html = runCatching { JSONArray("[$encodedHtml]").getString(0) }.getOrDefault("")
            val cookie = collectMissavCookies()
            when {
                html.isCloudflareChallengeHtml() -> {
                    statusText = "检测到 Cloudflare 验证页，请完成验证。完成后 Cookie 会自动保存。"
                }
                cookie.hasUsefulMissavCookie() || (cookie.isNotBlank() && html.isMissavReadyPage(number)) -> {
                    saved = true
                    statusText = "MissAV Cookie 已保存，正在返回并继续后台刮削..."
                    onSaveCookie(cookie)
                }
                else -> {
                    statusText = "页面正在加载，等待 MissAV 写入有效 Cookie..."
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A0E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101923))
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                text = "MissAV Cookie",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = statusText,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101923))
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = MissavScraper.USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                statusText = "MissAV WebView 渲染进程崩溃，请返回后重试。"
                                view?.stopLoading()
                                view?.destroy()
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                trySaveCookie(view)
                            }
                        }
                        loadUrl(missavUrl)
                    }
                },
                update = { trySaveCookie(it) }
            )
        }
    }
}

private fun collectMissavCookies(): String {
    CookieManager.getInstance().flush()
    val cookies = listOf(
        CookieManager.getInstance().getCookie(MISSAV_HOME).orEmpty(),
        CookieManager.getInstance().getCookie("$MISSAV_HOME/ja").orEmpty(),
        CookieManager.getInstance().getCookie("$MISSAV_HOME/cn").orEmpty(),
        CookieManager.getInstance().getCookie("https://www.missav.ai").orEmpty()
    )
    return cookies
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}

private fun String.isCloudflareChallengeHtml(): Boolean {
    val lower = lowercase()
    return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower)
}

private fun String.hasUsefulMissavCookie(): Boolean {
    if (isBlank()) return false
    val lower = lowercase()
    return "cf_clearance=" in lower ||
        "missav_session=" in lower ||
        "xsrf-token=" in lower ||
        "remember_web_" in lower
}

private fun String.isMissavReadyPage(number: String): Boolean {
    if (isCloudflareChallengeHtml()) return false
    val lower = lowercase()
    val normalized = number.lowercase().replace("_", "-")
    val compact = normalized.replace("-", "")
    return "missav" in lower &&
        (normalized in lower || compact in lower || "og:title" in lower || "space-y-2" in lower)
}

private const val MISSAV_HOME = "https://missav.ai"
