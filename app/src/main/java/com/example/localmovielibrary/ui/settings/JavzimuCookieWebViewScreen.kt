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
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JavzimuCookieWebViewScreen(
    url: String,
    onBack: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    var lastCookie by remember { mutableStateOf("") }
    var statusText by remember {
        mutableStateOf("请在此页面完成 Javzimu 的验证码。完成后点击返回，会保存 Cookie 并继续搜索字幕。")
    }

    fun saveAndBack() {
        val cookie = collectJavzimuCookies().ifBlank { lastCookie }
        if (cookie.isNotBlank()) onSaveCookie(cookie) else onBack()
    }

    BackHandler { saveAndBack() }

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
            IconButton(onClick = ::saveAndBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                text = "Javzimu 验证",
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
                        settings.userAgentString = DEFAULT_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                statusText = "Javzimu WebView 渲染进程崩溃，请返回后重试。"
                                view?.stopLoading()
                                view?.destroy()
                                return true
                            }

                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                lastCookie = collectJavzimuCookies()
                                statusText = if (lastCookie.isNotBlank()) {
                                    "已获取 Cookie。若验证码已经完成，请点击返回继续搜索。"
                                } else {
                                    "请完成页面中的验证码，然后点击返回。"
                                }
                            }
                        }
                        loadUrl(url)
                    }
                }
            )
        }
    }
}

private fun collectJavzimuCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie("https://javzimu.com").orEmpty(),
        CookieManager.getInstance().getCookie("https://www.javzimu.com").orEmpty()
    )
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}
