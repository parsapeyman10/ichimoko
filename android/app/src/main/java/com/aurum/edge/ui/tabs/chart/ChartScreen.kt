package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** A direct TradingView page; if Android WebView cannot reach it, the real site is one tap away. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String) {
    val uriHandler = LocalUriHandler.current
    val widgetUrl = remember(initialTicker) { TradingViewEmbed.widgetUrl(initialTicker) }
    val browserUrl = remember(initialTicker) { TradingViewEmbed.browserUrl(initialTicker) }
    var failure by remember(initialTicker) { mutableStateOf<String?>(null) }
    var usingSite by remember(initialTicker) { mutableStateOf(false) }
    var offerBrowser by remember(initialTicker) { mutableStateOf(false) }
    LaunchedEffect(initialTicker) {
        delay(12_000L)
        // A page can finish loading while its chart stays blank. Keep the real-site escape hatch.
        offerBrowser = true
    }
    Box(Modifier.fillMaxSize()) {
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
                        webViewClient = object : WebViewClient() {
                            private fun tryFullSite(view: WebView, message: String) {
                                if (!usingSite) {
                                    usingSite = true
                                    // If the embed endpoint itself is blocked, try TradingView's
                                    // own full chart in this same WebView before giving up.
                                    view.loadUrl(browserUrl)
                                } else failure = message
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                                         error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) tryFullSite(view, "TradingView در WebView بارگذاری نشد")
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                                                             errorResponse: WebResourceResponse) {
                                if (request.isForMainFrame) tryFullSite(view, "TradingView پاسخ HTTP ${errorResponse.statusCode} داد")
                            }
                        }
                        loadUrl(widgetUrl)
                    }
                },
                onReset = null,
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.destroy()
                },
            )
        }
        if (failure != null || offerBrowser) {
            OutlinedButton(
                onClick = {
                    runCatching { uriHandler.openUri(browserUrl) }
                        .onFailure { failure = "مرورگری برای بازکردن TradingView پیدا نشد" }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { Text(if (failure == null) "بازکردن در مرورگر" else "$failure · بازکردن در مرورگر") }
        }
    }
}
