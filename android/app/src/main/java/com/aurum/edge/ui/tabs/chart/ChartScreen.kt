package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Only one TradingView widget. No overlay, fallback page, chart controls or browser button. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String) {
    key(initialTicker) {
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
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    loadUrl(TradingViewEmbed.widgetUrl(initialTicker))
                }
            },
            onReset = null,
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}
