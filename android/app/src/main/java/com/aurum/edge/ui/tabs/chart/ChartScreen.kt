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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه چارت‌ها (Multi-Chart Screen):
 * شامل ۵ پنجره آنلاین تریدینگ‌ویو همزمان:
 * ۱. پنجره اصلی در بالاترین بخش (پیش‌فرض طلای جهانی XAU/USD با قابلیت تغییر به ۵۰+ نماد دلخواه)
 * ۲ الی ۵. چهار پنجره اختصاصی زنده برای معاملات ۴ دسته دارایی (کالا، فارکس، رمزارز، سهام)
 */
@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {

        // ═════════════════════════════════════════════════════════════════════
        // چارت شماره ۱ (بالاترین پنجره): چارت اصلی و اختصاصی دستی
        // پیش‌فرض روی طلای جهانی (XAU/USD) با امکان تغییر به هر نماد دیگر
        // ═════════════════════════════════════════════════════════════════════
        SectionCard(
            title = "⭐ چارت اصلی ۱ (سفارشی و آزاد) · ${market.symbol}",
            subtitle = "پیش‌فرض روی طلای جهانی · امکان انتخاب هر یک از ۵۰+ نماد با ابر ایچیموکو",
            trailing = {
                Pill(
                    text = market.feed.mode.label,
                    color = if (market.feed.mode == FeedMode.LIVE) AurumColors.Green else AurumColors.Gold,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            // ۱.۱ جستجو و تغییر نماد چارت اصلی
            SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

            // ۱.۲ انتخاب تایم‌فریم برای چارت اصلی
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
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

            // ۱.۳ پنجره تریدینگ‌ویو چارت اصلی
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .padding(top = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            ) {
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    widgetId = "chart_primary",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // چارت‌های شماره ۲ الی ۵: چهار پنجره تریدینگ‌ویو اختصاصی برای ۴ دسته دارایی
        // ═════════════════════════════════════════════════════════════════════
        val categories = listOf(
            AssetClass.COMMODITY to "XAU/USD",
            AssetClass.FOREX to "EUR/USD",
            AssetClass.CRYPTO to "BTCUSDT",
            AssetClass.STOCK to "AAPL",
        )

        categories.forEachIndexed { index, (assetClass, defaultBenchmark) ->
            val tradeForClass = openTrades.firstOrNull { it.assetClass == assetClass }
            val chartNumber = index + 2
            val activeSymbol = tradeForClass?.symbol ?: defaultBenchmark
            val activeInterval = tradeForClass?.interval ?: market.interval
            val isOpen = tradeForClass != null

            SectionCard(
                title = "چارت $chartNumber (${assetClass.label}) · $activeSymbol",
                subtitle = if (isOpen) "پنجره زنده معاملهٔ فعال در پورتفو با ابر ایچیموکو" else "پنجره آنلاین پایش و آماده‌باش دسته ${assetClass.label}",
                trailing = {
                    Pill(
                        text = if (isOpen) "● معامله باز (${tradeForClass?.action?.name})" else "○ آماده معامله",
                        color = if (isOpen) AurumColors.Green else AurumColors.Red,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                if (isOpen && tradeForClass != null) {
                    val currentPrice = livePrices[tradeForClass.symbol] ?: tradeForClass.entry
                    val pnlPerUnit = if (tradeForClass.action == SignalAction.BUY) currentPrice - tradeForClass.entry else tradeForClass.entry - currentPrice
                    val grossPnl = tradeForClass.positionOz * pnlPerUnit
                    val netPnl = grossPnl - (tradeForClass.effectiveCommissionUsd + tradeForClass.effectiveSpreadCostUsd)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("اهرم ${tradeForClass.effectiveLeverage}x", AurumColors.Cyan)
                            Pill("مارجین $${String.format(java.util.Locale.US, "%.1f", tradeForClass.effectiveMarginUsd)}", AurumColors.Gold)
                        }
                        Pill(
                            text = "PnL: " + (if (netPnl >= 0) "+$" else "-$") + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(netPnl)),
                            color = if (netPnl >= 0) AurumColors.Green else AurumColors.Red,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("ورود: ${formatPrice(tradeForClass.entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Text("SL: ${formatPrice(tradeForClass.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                        Text("TP: ${formatPrice(tradeForClass.takeProfit)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "در حال حاضر معاملهٔ بازی در دستهٔ ${assetClass.label} باز نیست (نمایش نماد پیش‌فرض $defaultBenchmark)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Pill("اهرم ${com.aurum.edge.core.PaperOrderRules.defaultLeverageFor(defaultBenchmark)}x", AurumColors.TextMuted)
                    }
                }

                // پنجره اختصاصی تریدینگ‌ویو
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            (if (isOpen) AurumColors.Green else AurumColors.Surface).copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    TradingViewWidget(
                        symbol = activeSymbol,
                        interval = activeInterval,
                        widgetId = "chart_slot_$chartNumber",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    widgetId: String,
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
    val html = remember(tvSymbol, tvInterval, widgetId) { tradingViewHtml(tvSymbol, tvInterval, widgetId) }
    val loadKey = "$tvSymbol|$tvInterval|$widgetId"

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

private fun tradingViewHtml(tvSymbol: String, tvInterval: String, widgetId: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tv_$widgetId" +
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
          <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
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

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                if (expanded) "▲ بستن لیست نمادها" else "▼ تغییر نماد چارت اصلی (۵۰+ نماد)",
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
