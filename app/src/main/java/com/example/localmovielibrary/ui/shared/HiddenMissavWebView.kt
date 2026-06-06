package com.example.localmovielibrary.ui.shared

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.localmovielibrary.scraper.MissavScraper
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.ByteArrayInputStream

data class HiddenMissavWebRequest(
    val id: Long,
    val number: String,
    val url: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HiddenMissavWebView(
    request: HiddenMissavWebRequest,
    onHtmlReady: (requestId: Long, html: String, cookie: String) -> Unit,
    onFailed: (requestId: Long, message: String) -> Unit
) {
    var completed by remember(request.id) { mutableStateOf(false) }

    fun failOnce(message: String) {
        if (completed) return
        completed = true
        onFailed(request.id, message)
    }

    LaunchedEffect(request.id) {
        delay(25_000)
        failOnce("MissAV WebView 抓取超时，可能需要重新获取 Cookie")
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .zIndex(-1f),
        factory = { context ->
            WebView(context).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = MissavScraper.USER_AGENT
                settings.mediaPlaybackRequiresUserGesture = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                }
                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        failOnce("MissAV WebView 渲染进程崩溃，已停止本次抓取")
                        view?.stopLoading()
                        view?.destroy()
                        return true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.shouldBlockForHiddenMissav()) {
                            return emptyWebResponse()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (completed || view == null) return
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                            if (completed) return@evaluateJavascript
                            val html = runCatching { JSONArray("[$encodedHtml]").getString(0) }.getOrDefault("")
                            when {
                                html.isBlank() -> Unit
                                html.isCloudflareChallengeHtml() -> Unit
                                html.isMissavUsableHtml(request.number) -> {
                                    completed = true
                                    onHtmlReady(request.id, html, collectMissavCookies())
                                }
                            }
                        }
                    }
                }
                loadUrl(request.url)
            }
        },
        update = { webView ->
            if (!completed && webView.url != request.url) {
                webView.loadUrl(request.url)
            }
        }
    )
}

private fun collectMissavCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie("https://missav.ai").orEmpty(),
        CookieManager.getInstance().getCookie("https://missav.ai/cn").orEmpty(),
        CookieManager.getInstance().getCookie("https://www.missav.ai").orEmpty()
    )
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

private fun String.isMissavUsableHtml(number: String): Boolean {
    if (isCloudflareChallengeHtml()) return false
    val lower = lowercase()
    val normalized = number.lowercase().replace("_", "-")
    val compact = normalized.replace("-", "")
    return "missav" in lower &&
        (normalized in lower || compact in lower || "og:title" in lower || "space-y-2" in lower || "text-nord6" in lower)
}

private fun String.shouldBlockForHiddenMissav(): Boolean {
    val lower = lowercase()
    if ("android-webview-video-poster" in lower) return true
    return blockedHiddenMissavSuffixes.any { lower.endsWith(it) } ||
        blockedHiddenMissavFragments.any { it in lower }
}

private val blockedHiddenMissavSuffixes = setOf(
    ".jpg",
    ".jpeg",
    ".png",
    ".gif",
    ".webp",
    ".avif",
    ".svg",
    ".mp4",
    ".m3u8",
    ".ts",
    ".webm",
    ".mov",
    ".avi",
    ".mkv",
    ".mp3",
    ".m4a",
    ".wav",
    ".ogg",
    ".woff",
    ".woff2",
    ".ttf",
    ".otf"
)

private val blockedHiddenMissavFragments = setOf(
    "/widgets/player/",
    "/widgets/v4/",
    "creative.",
    "bluetrafficstream",
    "myavlive",
    "xxxvjmp",
    "tsyndicate"
)

private fun emptyWebResponse(): WebResourceResponse {
    return WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0))
    )
}
