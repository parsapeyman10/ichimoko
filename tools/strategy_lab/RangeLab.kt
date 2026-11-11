import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.domain.Candle
import java.io.File
import java.util.Locale

/**
 * «آیا در بازار رِنج معامله می‌کنیم؟» — اندازه‌گیری، نه حدس.
 *
 * سؤال کاربر: «پارسا باید مولتی‌تایم‌فریم بررسی کند؛ دیدم در زمان رِنج بودن معامله کرده.»
 * برای اینکه پاسخ با عدد داده شود، هر معاملهٔ بک‌تست را با **وضعیت بازار در لحظهٔ ورود**
 * برچسب می‌زنیم و بعد می‌گوییم کدام دسته سود می‌دهد و کدام فقط کارمزد می‌خورد:
 *
 *  • ADX تایم ماشه (< ۱۵ / ۱۵–۲۰ / ۲۰–۲۵ / ≥ ۲۵)
 *  • موقعیت قیمت نسبت به کومو (داخل ابر / بیرون در جهت معامله / بیرون در خلاف جهت)
 *  • فاصلهٔ تنکان−کیجون بر حسب ATR (سطح رِنج بودن)
 *  • هم‌راستایی با تایم بالاتر (کومو در جهت معامله یا نه)
 *
 * استفاده: tools/strategy_lab/run.sh 0 0 rng <csv> [SYMBOL] [strategyId]
 */
private fun readCsv(path: String): List<Candle> {
    val f = File(path)
    require(f.isFile) { "فایل پیدا نشد: $path" }
    return BacktestCsvReader.parse(raw = f.readText(), fileName = f.name).candles
}

private class Bucket(val name: String) {
    var trades = 0
    var wins = 0
    var netUsd = 0.0
    var rSum = 0.0
    var grossWin = 0.0
    var grossLoss = 0.0
    fun add(trade: BacktestReplayEngine.Trade) {
        trades++
        val pnl = trade.netPnlUsd
        netUsd += pnl
        rSum += trade.rMultiple
        if (pnl > 0) {
            wins++
            grossWin += pnl
        } else {
            grossLoss += -pnl
        }
    }
    fun row(): String = String.format(
        Locale.US,
        "%-34s معامله=%4d · برد=%5.1f%% · PF=%5.2f · انتظار=%+6.3fR · خالص=%+9.2f$",
        name, trades, if (trades == 0) 0.0 else wins * 100.0 / trades,
        if (grossLoss <= 0.0) 99.99 else grossWin / grossLoss,
        if (trades == 0) 0.0 else rSum / trades, netUsd,
    )
}

fun main(args: Array<String>) {
    val path = args.getOrNull(2) ?: error("مسیر CSV لازم است")
    val symbol = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "XAU/USD"
    val strategyId = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: BitcoinStrategyModel.BASE_ICHIMOKU.id
    val candles = readCsv(path)
    val triggerInterval = when {
        path.contains("-m5-") -> com.aurum.edge.domain.Interval.M5
        path.contains("-m30-") -> com.aurum.edge.domain.Interval.M30
        else -> com.aurum.edge.domain.Interval.M15
    }
    println("نماد=$symbol · استراتژی=$strategyId · تایم=$triggerInterval.label · کندل=${candles.size}")

    val config = BacktestReplayEngine.Config(
        strategyId = strategyId,
        symbol = symbol,
        triggerInterval = triggerInterval,
        captureEntryReports = true,
    )
    val result = BacktestReplayEngine.run(candles, config) { }
    val st = result.totals
    println(
        String.format(
            Locale.US,
            "کل: معامله=%d · برد=%.1f%% · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$ · %.1fs",
            st?.trades ?: 0, st?.winRatePct ?: 0.0, st?.profitFactor ?: 0.0,
            st?.expectancyR ?: 0.0, st?.netPnlUsd ?: 0.0, result.elapsedMs / 1000.0,
        ),
    )

    // ── سری H1/H4 برای سنجش «رِنج تایم بالا» ────────────────────────────────
    // ADX تایم ماشه جواب سؤال کاربر نیست: رِنجی که او دیده، رِنجِ تایم بالاتر است که
    // معامله را در جهت غلط می‌فرستد. این‌جا H1/H4 از همان کندل‌ها ساخته می‌شود و
    // «کارایی روند» (Efficiency Ratio) هر لحظه حساب می‌شود:
    //   ER = |close − close[n−k]| ÷ مجموع |تغییر کندل‌به‌کندل|
    // ER بالا = حرکت مستقیم (روند) · ER پایین = بالا-پایین رفتن (رِنج).
    fun resample(src: List<Candle>, factor: Int): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>(src.size / factor + 2)
        var i = 0
        while (i < src.size) {
            val chunk = src.subList(i, minOf(i + factor, src.size))
            out += chunk.last().time to chunk.last().close
            i += factor
        }
        return out
    }
    fun efficiencyRatio(series: List<Pair<Long, Double>>, atMs: Long, lookback: Int): Double {
        // آخرین کندلی که زمانش از atMs گذشته نیست.
        var hi = series.size - 1
        var lo = 0
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (series[mid].first <= atMs) {
                found = mid; lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < lookback) return Double.NaN
        val from = found - lookback
        val net = kotlin.math.abs(series[found].second - series[from].second)
        var path = 0.0
        for (k in from + 1..found) path += kotlin.math.abs(series[k].second - series[k - 1].second)
        return if (path <= 1e-12) 0.0 else net / path
    }
    val h1 = resample(candles, 4)
    val h4 = resample(candles, 16)

    val h1Buckets = linkedMapOf(
        "H1 رِنج (ER < 0.20)" to Bucket("H1 رِنج (ER < 0.20)"),
        "H1 نیمه‌روند (ER 0.20–0.35)" to Bucket("H1 نیمه‌روند (ER 0.20–0.35)"),
        "H1 روند (ER ≥ 0.35)" to Bucket("H1 روند (ER ≥ 0.35)"),
    )
    val h4Buckets = linkedMapOf(
        "H4 رِنج (ER < 0.20)" to Bucket("H4 رِنج (ER < 0.20)"),
        "H4 نیمه‌روند (ER 0.20–0.35)" to Bucket("H4 نیمه‌روند (ER 0.20–0.35)"),
        "H4 روند (ER ≥ 0.35)" to Bucket("H4 روند (ER ≥ 0.35)"),
    )

    val reports: Map<Long, StrategyEvaluationReport> = result.entryReports
    fun reportOf(trade: BacktestReplayEngine.Trade): StrategyEvaluationReport? =
        reports[trade.entryTime] ?: reports.entries.minByOrNull { kotlin.math.abs(it.key - trade.entryTime) }?.value

    val adxBuckets = linkedMapOf(
        "ADX < 15 (رِنج خالص)" to Bucket("ADX < 15 (رِنج خالص)"),
        "ADX 15–20 (ضعیف)" to Bucket("ADX 15–20 (ضعیف)"),
        "ADX 20–25 (متوسط)" to Bucket("ADX 20–25 (متوسط)"),
        "ADX ≥ 25 (روند)" to Bucket("ADX ≥ 25 (روند)"),
    )
    val posBuckets = linkedMapOf(
        "قیمت داخل کومو (رِنج)" to Bucket("قیمت داخل کومو (رِنج)"),
        "قیمت بیرون کومو، هم‌جهت" to Bucket("قیمت بیرون کومو، هم‌جهت"),
        "قیمت بیرون کومو، خلاف‌جهت" to Bucket("قیمت بیرون کومو، خلاف‌جهت"),
        "بدون گزارش ورود" to Bucket("بدون گزارش ورود"),
    )
    val spreadBuckets = linkedMapOf(
        "|تنکان−کیجون| < 0.20 ATR" to Bucket("|تنکان−کیجون| < 0.20 ATR"),
        "|تنکان−کیجون| 0.20–0.45 ATR" to Bucket("|تنکان−کیجون| 0.20–0.45 ATR"),
        "|تنکان−کیجون| ≥ 0.45 ATR" to Bucket("|تنکان−کیجون| ≥ 0.45 ATR"),
        "بدون گزارش ورود" to Bucket("بدون گزارش ورود"),
    )
    val macroBuckets = linkedMapOf(
        "تایم بالا هم‌جهت" to Bucket("تایم بالا هم‌جهت"),
        "تایم بالا خلاف/داخل" to Bucket("تایم بالا خلاف/داخل"),
        "بدون گزارش ورود" to Bucket("بدون گزارش ورود"),
    )

    var rangedBoth = Bucket("رِنج دوشرطی (ADX<20 و داخل کومو)")
    var trendingOnly = Bucket("روند دوشرطی (ADX≥20 و بیرون کومو)")

    result.trades.forEach { trade ->
        val rep = reportOf(trade)
        val snap = rep?.triggerSnapshot
        val isBuy = trade.action == com.aurum.edge.domain.SignalAction.BUY
        if (snap == null) {
            adxBuckets.getValue("ADX < 15 (رِنج خالص)") // no-op guard
            posBuckets.getValue("بدون گزارش ورود").add(trade)
            spreadBuckets.getValue("بدون گزارش ورود").add(trade)
            macroBuckets.getValue("بدون گزارش ورود").add(trade)
            return@forEach
        }
        val adx = snap.adx14
        when {
            adx < 15.0 -> adxBuckets.getValue("ADX < 15 (رِنج خالص)")
            adx < 20.0 -> adxBuckets.getValue("ADX 15–20 (ضعیف)")
            adx < 25.0 -> adxBuckets.getValue("ADX 20–25 (متوسط)")
            else -> adxBuckets.getValue("ADX ≥ 25 (روند)")
        }.add(trade)

        val kumoTop = maxOf(snap.currentSpanA, snap.currentSpanB)
        val kumoBottom = minOf(snap.currentSpanA, snap.currentSpanB)
        val close = rep.livePrice
        val insideKumo = close in kumoBottom..kumoTop
        val aligned = if (isBuy) close > kumoTop else close < kumoBottom
        when {
            insideKumo -> posBuckets.getValue("قیمت داخل کومو (رِنج)")
            aligned -> posBuckets.getValue("قیمت بیرون کومو، هم‌جهت")
            else -> posBuckets.getValue("قیمت بیرون کومو، خلاف‌جهت")
        }.add(trade)

        val tkSpread = kotlin.math.abs(snap.tenkan - snap.kijun) / snap.atr14.coerceAtLeast(1e-9)
        when {
            tkSpread < 0.20 -> spreadBuckets.getValue("|تنکان−کیجون| < 0.20 ATR")
            tkSpread < 0.45 -> spreadBuckets.getValue("|تنکان−کیجون| 0.20–0.45 ATR")
            else -> spreadBuckets.getValue("|تنکان−کیجون| ≥ 0.45 ATR")
        }.add(trade)

        // هم‌راستایی با تایم بالاتر: اگر ابر تایم بالاتر خلاف جهت معامله باشد ⇒ خلاف‌جهت.
        val macroAligned = if (isBuy) snap.isPriceAboveKumo else snap.isPriceBelowKumo
        if (macroAligned) macroBuckets.getValue("تایم بالا هم‌جهت") else macroBuckets.getValue("تایم بالا خلاف/داخل")
            .add(trade)

        val er1 = efficiencyRatio(h1, trade.entryTime, 20)
        if (!er1.isNaN()) {
            when {
                er1 < 0.20 -> h1Buckets.getValue("H1 رِنج (ER < 0.20)")
                er1 < 0.35 -> h1Buckets.getValue("H1 نیمه‌روند (ER 0.20–0.35)")
                else -> h1Buckets.getValue("H1 روند (ER ≥ 0.35)")
            }.add(trade)
        }
        val er4 = efficiencyRatio(h4, trade.entryTime, 20)
        if (!er4.isNaN()) {
            when {
                er4 < 0.20 -> h4Buckets.getValue("H4 رِنج (ER < 0.20)")
                er4 < 0.35 -> h4Buckets.getValue("H4 نیمه‌روند (ER 0.20–0.35)")
                else -> h4Buckets.getValue("H4 روند (ER ≥ 0.35)")
            }.add(trade)
        }

        if (adx < 20.0 && insideKumo) rangedBoth.add(trade)
        if (adx >= 20.0 && !insideKumo) trendingOnly.add(trade)
    }

    println("\n─── بر اساس ADX تایم ماشه ───")
    adxBuckets.values.forEach { println("  ${it.row()}") }
    println("\n─── بر اساس موقعیت نسبت به کومو ───")
    posBuckets.values.forEach { println("  ${it.row()}") }
    println("\n─── بر اساس فاصلهٔ تنکان−کیجون ───")
    spreadBuckets.values.forEach { println("  ${it.row()}") }
    println("\n─── هم‌راستایی با ابر تایم بالاتر ───")
    macroBuckets.values.forEach { println("  ${it.row()}") }
    println("\n─── رِنج/روند در H1 (ER بیست کندلی) ───")
    h1Buckets.values.forEach { println("  ${it.row()}") }
    println("\n─── رِنج/روند در H4 (ER بیست کندلی) ───")
    h4Buckets.values.forEach { println("  ${it.row()}") }
    // ── A/B: فیلتر رِنج تایم بالاتر روشن/خاموش ─────────────────────────────
    println("\n─── A/B فیلتر رِنج H1 ───")
    val filtered = BacktestReplayEngine.run(candles, config.copy(requireHigherTfTrend = true)) { }
    val fs = filtered.totals
    println(
        String.format(
            Locale.US,
            "بدون فیلتر: معامله=%d · برد=%.1f%% · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$",
            st?.trades ?: 0, st?.winRatePct ?: 0.0, st?.profitFactor ?: 0.0,
            st?.expectancyR ?: 0.0, st?.netPnlUsd ?: 0.0,
        ),
    )
    println(
        String.format(
            Locale.US,
            "با فیلتر:   معامله=%d · برد=%.1f%% · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$ · رد‌شده=%d",
            fs?.trades ?: 0, fs?.winRatePct ?: 0.0, fs?.profitFactor ?: 0.0,
            fs?.expectancyR ?: 0.0, fs?.netPnlUsd ?: 0.0, filtered.rangeBlockedEntries,
        ),
    )

    println("\n─── دو حالت ترکیبی ───")
    println("  ${rangedBoth.row()}")
    println("  ${trendingOnly.row()}")
}
