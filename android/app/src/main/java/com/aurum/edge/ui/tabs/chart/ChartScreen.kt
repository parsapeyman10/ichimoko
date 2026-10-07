package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.Interval
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه چارت (Chart Screen):
 * اختصاص داده شده به نمایش فول‌ویو و اختصاصی چارت آنلاین و زندهٔ TradingView با اندیکاتور ایچیموکو.
 * (تمامی اطلاعات سیگنال، شروط و کاندیداها طبق درخواست به تب «معامله» منتقل شده‌اند).
 */
@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {

        // ── ۱. جستجو و انتخاب سریع نماد (از ۵۰+ دارایی) ────────────────────
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

        // ── ۲. انتخاب تایم‌فریم معاملاتی ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Interval.entries.forEach { interval ->
                FilterChip(
                    selected = market.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                    label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        // ── ۳. چارت زنده و آنلاین TradingView با ابزارها و اندیکاتور ایچیموکو ───
        SectionCard(
            title = "چارت آنلاین و زنده · ${market.symbol} (${market.interval.label})",
            subtitle = "جریان آنلاین زنده و ابر ایچیموکو از مرجع TradingView",
            trailing = {
                Pill(
                    text = market.feed.mode.label,
                    color = if (market.feed.mode == FeedMode.LIVE) AurumColors.Green else AurumColors.Gold,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 6.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    modifier: Modifier = Modifier,
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.of(symbol) }
    val tvInterval = remember(interval) {
        when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }
    }
    val html = remember(tvSymbol, tvInterval) { tradingViewHtml(tvSymbol, tvInterval) }
    val loadKey = "$tvSymbol|$tvInterval"

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
                }
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                }
                tag = loadKey
                loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
    )
}

private fun tradingViewHtml(tvSymbol: String, tvInterval: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"

    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background-color: #0b0e13;
            }
            iframe {
              width: 100%;
              height: 100%;
              border: none;
            }
          </style>
        </head>
        <body>
          <iframe src="$iframeUrl" allowtransparency="true" frameborder="0"></iframe>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun SymbolSearchRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { SymbolSearch.rank(query, CryptoCatalog.symbols, 15) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected, style = MaterialTheme.typography.bodyMedium, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                Pill(AssetClass.of(selected).label, AurumColors.Surface)
            }
            Text(
                if (expanded) "▲ بستن لیست نمادها" else "▼ تغییر سهم / نماد (۵۰+ نماد)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("جستجوی نماد، طلا، نفت، رمزارزها یا سهام...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 6.dp)) {
                    items(results) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(item.id)
                                    expanded = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(item.id, style = MaterialTheme.typography.bodyMedium, color = AurumColors.TextPrimary)
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                            }
                            Pill(AssetClass.of(item.id).label, AurumColors.Surface)
                        }
                    }
                }
            }
        }
    }
}
