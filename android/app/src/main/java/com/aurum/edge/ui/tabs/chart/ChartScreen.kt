package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The Chart tab hosts exactly one TradingView Advanced Chart; no app chart UI or feed gates. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String) {
    val html = TradingViewEmbed.html(initialTicker)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                tag = initialTicker
                loadDataWithBaseURL("https://www.tradingview.com/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // Only an explicit chart navigation with another instrument changes the opening view.
            if (webView.tag != initialTicker) {
                webView.tag = initialTicker
                webView.loadDataWithBaseURL("https://www.tradingview.com/", html, "text/html", "UTF-8", null)
            }
        },
        onReset = null,
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
