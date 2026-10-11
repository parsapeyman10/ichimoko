package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/** One official TradingView advanced-chart embed. Never a made-up chart or a second site. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String) {
    val html = remember(initialTicker) { TradingViewEmbed.html(initialTicker) }
    var failure by remember(initialTicker) { mutableStateOf<String?>(null) }
    var view by remember(initialTicker) { mutableStateOf<WebView?>(null) }
    LaunchedEffect(initialTicker, html) {
        if (html != null) {
            delay(18_000L)
            // Page-finished is not proof that the async embed script made a chart iframe.
            view?.evaluateJavascript("document.querySelector('iframe') !== null") { created ->
                if (created != "true" && failure == null)
                    failure = "ویجت TradingView ساخته نشد؛ بارگذاری اسکریپت یا دسترسی شبکه را در گوشی بررسی کنید"
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (html != null) key(initialTicker) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.rgb(11, 14, 19))
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            private fun fromTradingView(request: WebResourceRequest): Boolean {
                                val host = request.url.host.orEmpty()
                                return host == "tradingview.com" || host.endsWith(".tradingview.com") ||
                                    host == "tradingview-widget.com" || host.endsWith(".tradingview-widget.com")
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (fromTradingView(request))
                                    failure = "دریافت TradingView در WebView ناموفق بود (کد ${error.errorCode})؛ وضعیت شبکه/Android System WebView را بررسی کنید"
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (fromTradingView(request) && response.statusCode >= 400)
                                    failure = "TradingView پاسخ HTTP ${response.statusCode} داد؛ نمودار روی این شبکه تأیید نشد"
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                failure = "گواهی امن TradingView تأیید نشد؛ بارگذاری متوقف شد"
                            }
                        }
                        loadDataWithBaseURL("https://www.tradingview.com/", html, "text/html", "UTF-8", null)
                        view = this
                    }
                },
                onReset = null,
                onRelease = { webView ->
                    if (view === webView) view = null
                    webView.stopLoading()
                    webView.destroy()
                },
            )
        }
        val message = if (html == null) "نماد TradingView معتبر نیست؛ چارت نماد دیگری جایگزین نمی‌شود" else failure
        if (message != null) Text(message,
            modifier = Modifier.align(Alignment.Center).padding(20.dp),
            color = AurumColors.Gold)
    }
}
