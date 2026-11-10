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
    // تایم‌فریم از نام فایل خوانده می‌شود؛ پیش از این همیشه M15 می‌ماند و «۵ دقیقه» در واقع
    // ۱۵ دقیقه‌ی بازنمونه‌گیری‌شده بود — اندازه‌گیری غلط، ادعای غلط.
    val triggerInterval = when {
        triggerPath.contains("-m5-") -> com.aurum.edge.domain.Interval.M5
        triggerPath.contains("-m30-") -> com.aurum.edge.domain.Interval.M30
        else -> com.aurum.edge.domain.Interval.M15
    }
    val strategy = strategyPath?.let { readCsv(it) }
    val macro = macroPath?.let { readCsv(it) }
    println(
        "نماد=$symbol · تایم ماشه=${triggerInterval.label} · ضریب هزینه=" +
            "${System.getenv("HX_COST_MULT") ?: "1"} · کندل ماشه=${trigger.size} · " +
            "۱ساعته=${strategy?.size ?: 0} · ۴ساعته=${macro?.size ?: 0}",
    )

    fun run(label: String, id: String, hiddenLayer: Boolean) {
        val cfg = BacktestReplayEngine.Config(
            strategyId = id,
            symbol = symbol,
            triggerInterval = triggerInterval,
            costMultiplier = System.getenv("HX_COST_MULT")?.toDoubleOrNull() ?: 1.0,
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
        // ممیزی مستقل: فهرست معامله‌ها را بیرون می‌نویسیم تا آمار موتور با محاسبهٔ مستقل
        // (پایتون) مقایسه شود — «آیا این اعداد از همان معامله‌ها می‌آیند؟»
        if (System.getenv("HX_DUMP") != null) {
            val safe = label.replace(Regex("[^A-Za-z]"), "_")
            // مسیر با File(dir, name) ساخته می‌شود؛ پیش‌تر با replace("/") نام فایل به ریشهٔ
            // مخزن می‌افتاد.
            val f = File("/tmp", "hx-trades-${symbol.replace("/", "")}-$safe.csv")
            f.writeText(
                buildString {
                    appendLine("idx,action,entryTime,entry,sl,tp,exitTime,exitPrice,reason,gross,fee,net,r,barsHeld,equityAfter")
                    res.trades.forEach { t ->
                        appendLine(
                            listOf(
                                t.index, t.action.name, t.entryTime, t.entryPrice, t.stopLoss, t.takeProfit,
                                t.exitTime ?: "", t.exitPrice ?: "", t.exitReason.name,
                                t.grossPnlUsd, t.feeUsd, t.netPnlUsd, t.rMultiple, t.barsHeld, t.equityAfterUsd,
                            ).joinToString(","),
                        )
                    }
                },
            )
            println("    ↳ معامله‌ها نوشته شد: ${f.path}")
        }
        // «۰ معامله» باید دلیل داشته باشد، نه سکوت: بیشترین شرط‌هایی که رد شده‌اند.
        if (res.trades.isEmpty()) {
            val blockers = res.topBlockers.take(4).joinToString(" · ") { "${it.first} (${it.second})" }
            println("    ↳ بیشترین شرط پاس‌شده=${res.maxConditionsPassed} · سدها: ${blockers.ifBlank { "—" }}")
        }
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
