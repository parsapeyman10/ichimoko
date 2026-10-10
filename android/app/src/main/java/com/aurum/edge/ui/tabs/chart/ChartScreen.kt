package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.core.Interval
import com.aurum.edge.ui.theme.AurumColors

/** Visual-only TradingView widget; signals, paper orders and exits use the independent app feed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(symbol: String, interval: Interval) {
    val html = TradingViewEmbed.html(symbol, interval)
    if (html == null) {
        // Never show a different instrument as an undocumented fallback.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "برای $symbol نماد معادل در TradingView ثبت نشده است.",
                color = AurumColors.Gold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    val loadKey = "$symbol|$interval"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true // TradingView's official embed needs JavaScript.
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(false) // Chart gestures belong to TradingView, not WebView.
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    userAgentString = DESKTOP_USER_AGENT // Request the full widget toolbars on mobile.
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient() // SSL failures use WebView's default cancel behavior.
                tag = loadKey
                loadDataWithBaseURL("https://www.tradingview.com/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // Recomposition (e.g. a quote update) must not reset the chart or its drawings.
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://www.tradingview.com/", html, "text/html", "UTF-8", null)
            }
        },
        onReset = null,
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"
