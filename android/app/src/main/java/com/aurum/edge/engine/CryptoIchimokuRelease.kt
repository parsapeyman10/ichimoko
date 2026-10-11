package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Crypto-only, closed-bar evidence within the EXISTING four-layer signal engine.
 * A thin cloud / close Tenkan-Kijun is compression, not a forecast. The entry side is only
 * considered after a same-venue price-action breakout, Chikou clearance (price AND the cloud
 * at the Chikou's own historical position), and actual reported volume. No future candle is read.
 * The ordinary volatility, seven-frame, risk, quote and journal gates remain authoritative.
 */
internal object CryptoIchimokuRelease {
    data class Evidence(
        val ready: Boolean,
        val compressedBars: Int,
        val chikouClear: Boolean,
        val breakout: Boolean,
        val volumeConfirmed: Boolean,
        val structureConfirmed: Boolean,
        val detail: String,
    )

    fun assess(bars: List<Candle>, ichi: Ichimoku, atr: Double, side: SignalAction): Evidence {
        fun unavailable(detail: String) = Evidence(false, 0, false, false, false, false, detail)
        val i = bars.lastIndex
        val lag = i - ichi.displacement
        if (side == SignalAction.NO_TRADE || bars.size < 96 || i < 25 || lag < 2 ||
            !atr.isFinite() || atr <= 0.0 || bars.any { !it.closed })
            return unavailable("فشردگی/چیکوی رمزارز: کندل بسته یا ATR معتبر کافی نیست")

        val preceding = bars.subList(i - 20, i)
        val volumes = preceding.map { it.volume }
        if (volumes.any { !it.isFinite() || it <= 0.0 } ||
            !bars[i].volume.isFinite() || bars[i].volume <= 0.0)
            return unavailable("حجم واقعی ۲۰ کندل قبلی/کندل شکست موجود نیست؛ ورودِ شکست تأیید نشد")
        val volumeMedian = volumes.sorted()[volumes.size / 2]
        val volumeConfirmed = bars[i].volume >= volumeMedian * 1.2

        val compressed = (i - 5 until i).count { j ->
            val tenkan = ichi.tenkan.getOrNull(j)
            val kijun = ichi.kijun.getOrNull(j)
            val a = ichi.senkouA.getOrNull(j)
            val b = ichi.senkouB.getOrNull(j)
            tenkan != null && kijun != null && a != null && b != null &&
                abs(tenkan - kijun) <= 0.55 * atr && abs(a - b) <= 0.8 * atr
        }
        val read = IchimokuAlignment.read(bars, ichi, atr)
            ?: return unavailable("ابر و چیکو در محل زمانی درست قابل اندازه‌گیری نیستند")
        // Chikou is TODAY'S close plotted 24 bars back. The cloud THERE is cloudAt(lag),
        // not the projected cloud at today's index; lag +/-2 bars avoid a single-wick illusion.
        val oldBars = bars.subList(lag - 2, lag + 3)
        val oldClouds = (lag - 2..lag + 2).map { ichi.cloudAt(it)
            ?: return unavailable("ابر تاریخی در جای چیکو موجود نیست") }
        val buffer = 0.1 * atr
        val close = bars[i].close
        val chikouClear = if (side == SignalAction.BUY)
            close > max(oldBars.maxOf { it.high }, oldClouds.maxOf { max(it.first, it.second) }) + buffer
        else close < min(oldBars.minOf { it.low }, oldClouds.minOf { min(it.first, it.second) }) - buffer

        val previousTenkan = ichi.tenkan.getOrNull(i - 1)
        val previousA = ichi.senkouA.getOrNull(i - 1)
        val structure = if (previousTenkan == null || previousA == null) false
        else if (side == SignalAction.BUY)
            close > max(read.visibleCloud.first, read.visibleCloud.second) &&
                read.tenkan > read.kijun && read.projectedCloud.first >= read.projectedCloud.second - 0.2 * atr &&
                read.tenkan >= previousTenkan - 0.1 * atr &&
                read.projectedCloud.first >= previousA - 0.1 * atr && read.priceKijunAtr <= 4.0
        else close < min(read.visibleCloud.first, read.visibleCloud.second) &&
            read.tenkan < read.kijun && read.projectedCloud.first <= read.projectedCloud.second + 0.2 * atr &&
            read.tenkan <= previousTenkan + 0.1 * atr &&
            read.projectedCloud.first <= previousA + 0.1 * atr && read.priceKijunAtr <= 4.0

        val candle = bars[i]
        val body = abs(candle.close - candle.open)
        val breakout = body >= 0.35 * atr && (if (side == SignalAction.BUY)
            close > preceding.maxOf { it.high } + buffer && close > candle.open
        else close < preceding.minOf { it.low } - buffer && close < candle.open)
        val ready = compressed >= 3 && chikouClear && structure && breakout && volumeConfirmed
        val detail = "فشردگی تنکان/کیجون و ابر پیش‌نمایش $compressed/۵ کندل · " +
            "چیکوی آزاد از قیمت و ابر تاریخی=${if (chikouClear) "تأیید" else "رد"} · " +
            "شکست بستهٔ سطح ۲۰ کندل=${if (breakout) "تأیید" else "رد"} · " +
            "ساختار و فاصله تا کیجون=${if (structure) "تأیید" else "رد"} · " +
            "حجم واقعی نسبت به میانهٔ ۲۰ کندل=" +
            String.format(java.util.Locale.US, "%.2f", candle.volume / volumeMedian) + " (حداقل ۱٫۲۰)"
        return Evidence(ready, compressed, chikouClear, breakout, volumeConfirmed, structure, detail)
    }
}
