package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Learning is now only chart replay/backtest through GoCharting. */
@Composable
fun LearnScreen(viewModel: AurumViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard(
            title = "Replay و Backtest روی نمودار",
            subtitle = "GoCharting داخل برنامه؛ برای تمرین قواعد موتور ایچیموکو روی چارت",
        ) {
            Text(
                "از ابزارهای Replay / Backtest خود GoCharting روی نمودار استفاده کن و قواعد موتور ایچیموکو را مرحله‌به‌مرحله تست کن. این بخش دیتای ساختگی یا فرم جداگانه ندارد.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(
                onClick = { clipboard.setText(AnnotatedString(AURUM_ICHIMOKU_STRATEGY)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { Text("کپی موتور ایچیموکو برای GoCharting / TradingView") }
            Text(
                "بعد از باز شدن GoCharting: بخش Lipi Script را باز کن، کد را Paste کن و به‌عنوان Strategy اجرا/Backtest بگیر. همین کد Pine-compatible برای TradingView Strategy Tester هم قابل استفاده است.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            GoChartingWebView(Modifier.fillMaxWidth().height(620.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoChartingWebView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadUrl("https://gocharting.com/terminal")
            }
        },
        update = { webView ->
            if (webView.url.isNullOrBlank()) webView.loadUrl("https://gocharting.com/terminal")
        },
    )
}

private val AURUM_ICHIMOKU_STRATEGY = """
//@version=5
strategy("AURUM Ichimoku Engine - Paper Rules", overlay=true, initial_capital=10000, commission_type=strategy.commission.cash_per_contract, commission_value=0.05)

// Engine settings: 1m uses 7/22/44; 5m+ uses 9/26/52. Change manually if needed.
tenkanLen = input.int(9, "Tenkan", minval=1)
kijunLen  = input.int(26, "Kijun", minval=1)
spanBLen  = input.int(52, "Span B", minval=1)
disp      = input.int(26, "Displacement", minval=0)
minScore  = input.int(72, "Minimum score", minval=50, maxval=100)

atr = ta.atr(14)
tenkan = (ta.highest(high, tenkanLen) + ta.lowest(low, tenkanLen)) / 2.0
kijun = (ta.highest(high, kijunLen) + ta.lowest(low, kijunLen)) / 2.0
spanA = (tenkan + kijun) / 2.0
spanB = (ta.highest(high, spanBLen) + ta.lowest(low, spanBLen)) / 2.0
ema200 = ta.ema(close, 200)
vwapValue = ta.vwap(hlc3)
rsi7 = ta.rsi(close, 7)
cloudTop = math.max(spanA[disp], spanB[disp])
cloudBot = math.min(spanA[disp], spanB[disp])

freshBullCross = (ta.crossover(tenkan, kijun) or (tenkan > kijun and tenkan[1] > kijun[1] and tenkan[2] <= kijun[2]))
freshBearCross = (ta.crossunder(tenkan, kijun) or (tenkan < kijun and tenkan[1] < kijun[1] and tenkan[2] >= kijun[2]))
priceAboveCloud = close > cloudTop + 0.08 * atr
priceBelowCloud = close < cloudBot - 0.08 * atr
cloudBull = spanA > spanB
cloudBear = spanA < spanB
chikouBuy = close > high[kijunLen]
chikouSell = close < low[kijunLen]
emaBuy = close > ema200
emaSell = close < ema200
vwapBuy = close > vwapValue
vwapSell = close < vwapValue
rsiBuy = rsi7 >= 52 and rsi7 <= 72
rsiSell = rsi7 >= 28 and rsi7 <= 48

buyScore = (freshBullCross ? 20 : 0) + (priceAboveCloud ? 18 : 0) + (cloudBull ? 10 : 0) + (chikouBuy ? 10 : 0) + (emaBuy ? 15 : 0) + (vwapBuy ? 12 : 0) + (rsiBuy ? 10 : 0)
sellScore = (freshBearCross ? 20 : 0) + (priceBelowCloud ? 18 : 0) + (cloudBear ? 10 : 0) + (chikouSell ? 10 : 0) + (emaSell ? 15 : 0) + (vwapSell ? 12 : 0) + (rsiSell ? 10 : 0)

atrWindow = ta.sma(atr, 60)
atrShock = atrWindow > 0 and atr > 2.5 * atrWindow
longOk = buyScore >= minScore and not atrShock
shortOk = sellScore >= minScore and not atrShock

longSL = close - 1.2 * atr
longTP = close + 1.8 * (close - longSL)
shortSL = close + 1.2 * atr
shortTP = close - 1.8 * (shortSL - close)

if longOk and strategy.position_size <= 0
    strategy.entry("AURUM-LONG", strategy.long)
    strategy.exit("AURUM-LONG-EXIT", "AURUM-LONG", stop=longSL, limit=longTP)
if shortOk and strategy.position_size >= 0
    strategy.entry("AURUM-SHORT", strategy.short)
    strategy.exit("AURUM-SHORT-EXIT", "AURUM-SHORT", stop=shortSL, limit=shortTP)

plot(tenkan, "Tenkan", color=color.aqua)
plot(kijun, "Kijun", color=color.purple)
plot(ema200, "EMA200", color=color.gray)
plot(vwapValue, "VWAP", color=color.orange)
pA = plot(spanA, "Senkou A", color=color.new(color.green, 30), offset=disp)
pB = plot(spanB, "Senkou B", color=color.new(color.red, 30), offset=disp)
fill(pA, pB, color=spanA > spanB ? color.new(color.green, 85) : color.new(color.red, 85))
plotshape(longOk, "AURUM BUY", shape.triangleup, location.belowbar, color=color.lime, size=size.small, text="BUY")
plotshape(shortOk, "AURUM SELL", shape.triangledown, location.abovebar, color=color.red, size=size.small, text="SELL")
""".trimIndent()
