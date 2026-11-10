import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.HiddenCrossEngine
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.domain.Candle
import java.io.File
import java.util.Locale

/**
 * سنجش A/B «هیدن کراس».
 *
 * سؤال کاربر: «هیدن کراس ضررده است؛ یعنی چیزی که مدرس گفته درست پیاده نشده.»
 * پیش از هر تغییری باید عدد داشته باشیم. این آزمایشگاه سه اجرا روی یک داده انجام می‌دهد:
 * ۱) استراتژی مستقل HIDDEN_CROSS
 * ۲) بیس + لایهٔ هیدن کراس (رفتار پیش‌فرض فعلی)
 * ۳) بیس بدون لایه (چهار مکتب خالص)
 * و «انتظار R»، «PF» و «تعداد معامله» هر سه را کنار هم می‌گذارد تا تفاوت لایه دیده شود.
 *
 * استفاده: tools/strategy_lab/run.sh 0 0 hx <trigger.csv> [1h.csv] [4h.csv] [SYMBOL]
 */
private fun readCsv(path: String): List<Candle> {
    val f = File(path)
    require(f.isFile) { "فایل پیدا نشد: $path" }
    return BacktestCsvReader.parse(raw = f.readText(), fileName = f.name).candles
}

fun main(args: Array<String>) {
    val triggerPath = args.getOrNull(2) ?: error("مسیر CSV ماشه لازم است")
    val strategyPath = args.getOrNull(3)?.takeIf { it.isNotBlank() && it != "-" }
    val macroPath = args.getOrNull(4)?.takeIf { it.isNotBlank() && it != "-" }
    val symbol = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "XAU/USD"

    val trigger = readCsv(triggerPath)
    val strategy = strategyPath?.let { readCsv(it) }
    val macro = macroPath?.let { readCsv(it) }
    println("نماد=$symbol · کندل ماشه=${trigger.size} · ۱ساعته=${strategy?.size ?: 0} · ۴ساعته=${macro?.size ?: 0}")

    fun run(label: String, id: String, hiddenLayer: Boolean) {
        val cfg = BacktestReplayEngine.Config(
            strategyId = id,
            symbol = symbol,
            includeHiddenCrossLayer = hiddenLayer,
            strategyCandlesOverride = strategy.orEmpty(),
            macroCandlesOverride = macro.orEmpty(),
            captureEntryReports = false,
        )
        val res = BacktestReplayEngine.run(trigger, cfg) { }
        val st = res.stats.firstOrNull()
        println(
            String.format(
                Locale.US,
                "%-34s → معامله=%3d · برد=%.0f%% · خالص=%+8.2f$ · PF=%.2f · انتظار=%+.3fR · حداکثر افت=%.1f%%",
                label,
                res.trades.size,
                st?.winRatePct ?: 0.0,
                st?.netPnlUsd ?: 0.0,
                st?.profitFactor ?: 0.0,
                st?.expectancyR ?: 0.0,
                st?.maxDrawdownPct ?: 0.0,
            ),
        )
    }

    // ── سرشماری قواعد: «آیا آن‌چه مدرس می‌گوید در داده دیده می‌شود؟» ──
    // بدون این عدد، هر بحثی دربارهٔ «درست پیاده نشده» حدس است. سه چیز شمرده می‌شود:
    // ۱) کندل‌هایی که تنکان و کیجون تقریباً برابرند (کراس برابری)
    // ۲) کندل‌هایی که ابر «مخفی/متوازن» است (ضخامت ابر زیر سقف مجاز)
    // ۳) کندل‌هایی که هر دو با هم برقرارند = زمینهٔ کامل هیدن کراس
    val bars = IchimokuStrategyEngine.computeChartSeries(trigger)?.bars.orEmpty()
    if (bars.isNotEmpty()) {
        var equalCross = 0
        var balancedCloud = 0
        var both = 0
        // ATR14 ساده روی همان کندل‌ها (بازاربرگ نمودار ATR ندارد).
        val atrs = DoubleArray(trigger.size)
        var sum = 0.0
        for (i in 1 until trigger.size) {
            val prevClose = trigger[i - 1].close
            val tr = maxOf(
                trigger[i].high - trigger[i].low,
                kotlin.math.abs(trigger[i].high - prevClose),
                kotlin.math.abs(trigger[i].low - prevClose),
            )
            sum += tr
            if (i > 14) sum -= atrs0(trigger, i - 14)
            atrs[i] = sum / minOf(i, 14).toDouble()
        }
        for (b in bars) {
            val atr = atrs.getOrElse(b.index) { 0.0 }
            if (!atr.isFinite() || atr <= 0.0) continue
            val eq = kotlin.math.abs(b.tenkan - b.kijun) <= HiddenCrossEngine.EQUAL_CROSS_TOLERANCE_ATR * atr
            val cloud = kotlin.math.abs(b.currentSpanA - b.currentSpanB) <=
                HiddenCrossEngine.HIDDEN_CLOUD_MAX_THICKNESS_ATR * atr
            if (eq) equalCross++
            if (cloud) balancedCloud++
            if (eq && cloud) both++
        }
        val n = bars.size.toDouble().coerceAtLeast(1.0)
        println(
            String.format(
                Locale.US,
                "سرشماری قواعد هیدن کراس → کندل=%d · کراس برابری=%.1f%% · ابر متوازن=%.1f%% · زمینهٔ کامل (هر دو)=%.1f%%",
                bars.size, equalCross / n * 100, balancedCloud / n * 100, both / n * 100,
            ),
        )
    }

    run("هیدن کراس (مستقل)", BitcoinStrategyModel.HIDDEN_CROSS.id, true)
    run("بیس + لایهٔ هیدن کراس", BitcoinStrategyModel.BASE_ICHIMOKU.id, true)
    run("بیس بدون لایه (چهار مکتب)", BitcoinStrategyModel.BASE_ICHIMOKU.id, false)
}

/** دامنهٔ درست (TR) کندل i برای میانگین ATR ساده. */
private fun atrs0(bars: List<Candle>, i: Int): Double {
    if (i <= 0 || i >= bars.size) return 0.0
    val prevClose = bars[i - 1].close
    return maxOf(
        bars[i].high - bars[i].low,
        kotlin.math.abs(bars[i].high - prevClose),
        kotlin.math.abs(bars[i].low - prevClose),
    )
}
