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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.AppNetwork
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.data.ChartSymbolChoices
import com.aurum.edge.ui.theme.AurumColors

/** A visual-only symbol selector and ONE TradingView iframe. It does not change the trade feed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(initialTicker: String, onSelectTicker: (String) -> Unit) {
    val route by AppNetwork.route.collectAsStateWithLifecycle()
    val html = remember(initialTicker) { TradingViewEmbed.html(initialTicker) }
    var showPicker by remember { mutableStateOf(false) }
    // Re-selecting the same ticker deliberately reloads M5 after an in-widget manual change.
    // Normal recompositions do not reload the third-party chart.
    var reloadCount by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // TradingView's mobile iframe may hide its own symbol search even with symboledit=1.
        // This small selector changes the SAME widget, not the app's live market or a second chart.
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
                Text("نماد · ${ChartSymbolChoices.display(initialTicker)}")
            }
            // Recreate the widget with its Ichimoku study if it was removed inside TradingView.
            TextButton(onClick = { reloadCount++ }) { Text("بازخوانی چارت ↻") }
        }
        if (!route.ready) {
            Text("شبکهٔ گوشی آماده نیست؛ اینترنت یا VPN سیستم را بررسی کنید",
                modifier = Modifier.padding(20.dp), color = AurumColors.Gold)
        } else if (html == null) {
            Text("نماد TradingView معتبر نیست؛ چارت نماد دیگری جایگزین نمی‌شود",
                modifier = Modifier.padding(20.dp), color = AurumColors.Gold)
        } else {
            // Preserve the historical iframe + mobile WebView bootstrap. Recomposition never
            // reloads it; selecting a different instrument replaces the old view exactly once.
            key(initialTicker, reloadCount, route.revision) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
    }
    if (showPicker) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("نماد TradingView") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("جستجوی نماد یا نام") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 370.dp)) {
                        items(ChartSymbolChoices.search(query), key = { it.id }) { choice ->
                            TextButton(onClick = {
                                if (choice.ticker == initialTicker) reloadCount++
                                else onSelectTicker(choice.ticker)
                                showPicker = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("${choice.id} · ${choice.label}", style = MaterialTheme.typography.bodyMedium)
                                    Text(choice.ticker, style = MaterialTheme.typography.labelSmall,
                                        color = AurumColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("بستن") } },
        )
    }
}

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"
