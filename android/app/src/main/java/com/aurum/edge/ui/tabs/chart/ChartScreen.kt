package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.ui.theme.AurumColors

/** A single TradingView iframe for the selected ticker. No other chart UI or trade windows. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String) {
    val html = remember(initialTicker) { TradingViewEmbed.html(initialTicker) }
    if (html == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("نماد TradingView معتبر نیست؛ چارت نماد دیگری جایگزین نمی‌شود",
                modifier = Modifier.padding(20.dp), color = AurumColors.Gold)
        }
        return
    }
    // Keep the iframe and mobile WebView bootstrap from the working run #350 implementation.
    // A new symbol gets one new view, never multiple views or repeated reloads on recomposition.
    key(initialTicker) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.rgb(11, 14, 19))
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        javaScriptCanOpenWindowsAutomatically = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // All resources must remain HTTPS; do not bypass WebView TLS validation.
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = MOBILE_USER_AGENT
                        textZoom = 100
                    }
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient() // default behavior cancels certificate errors
                    loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
                }
            },
            onReset = null,
            onRelease = { webView -> webView.stopLoading(); webView.destroy() },
        )
    }
}

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"
