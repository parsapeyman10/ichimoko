import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.domain.Candle
import java.io.File
import java.util.Locale

/**
 * بک‌تست «ماه‌به‌ماه» روی دادهٔ واقعی.
 *
 * سؤال کاربر: فایل سنگین یک‌جا درست اجرا نمی‌شود؛ بهتر است ماه‌به‌ماه تست بگیرد، همان ماه را
 * گزارش کند و بعد برود ماه بعد. این آزمایشگاه همان مسیرِ `BacktestReplayEngine.runPeriodic`
 * را روی فایل واقعی اجرا می‌کند تا هم جدول ماه‌ها دیده شود و هم اختلافش با اجرای یک‌جا
 * اندازه‌گیری شود (شمار معامله و انتظار R).
 *
 * استفاده: tools/strategy_lab/run.sh 0 0 mon <csv> [SYMBOL] [strategyId]
 */
private fun readCsvLocal(path: String): List<Candle> {
    val f = File(path)
    require(f.isFile) { "فایل پیدا نشد: $path" }
    return BacktestCsvReader.parse(raw = f.readText(), fileName = f.name).candles
}

fun main(args: Array<String>) {
    val path = args.getOrNull(2) ?: error("مسیر CSV لازم است")
    val symbol = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "XAU/USD"
    val strategyId = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: BitcoinStrategyModel.BASE_ICHIMOKU.id
    val candles = readCsvLocal(path)
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
        captureEntryReports = false,
    )

    // ۱) اجرای یک‌جا (مرجع مقایسه)
    var lastShown = -1
    val whole = BacktestReplayEngine.run(candles, config) { p ->
        val step = (p * 10).toInt()
        if (step != lastShown) {
            lastShown = step
        }
    }
    val wholeSt = whole.totals

    // ۲) اجرای ماه‌به‌ماه
    val periodic = BacktestReplayEngine.runPeriodic(candles, config)
    println(
        String.format(
            Locale.US,
            "یک‌جا:     معامله=%d · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$ · %.1fs",
            wholeSt?.trades ?: 0, wholeSt?.profitFactor ?: 0.0, wholeSt?.expectancyR ?: 0.0,
            wholeSt?.netPnlUsd ?: 0.0, whole.elapsedMs / 1000.0,
        ),
    )
    val tot = periodic.totals
    println(
        String.format(
            Locale.US,
            "ماه‌به‌ماه: معامله=%d · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$ · ماه=%d (خالی=%d) · %.1fs",
            tot?.trades ?: 0, tot?.profitFactor ?: 0.0, tot?.expectancyR ?: 0.0,
            tot?.netPnlUsd ?: 0.0, periodic.months, periodic.emptyMonths, periodic.elapsedMs / 1000.0,
        ),
    )
    val diff = (tot?.trades ?: 0) - (wholeSt?.trades ?: 0)
    println("اختلاف شمار معامله: ${if (diff >= 0) "+" else ""}$diff · اختلاف انتظار R: " +
        String.format(Locale.US, "%+.3f", (tot?.expectancyR ?: 0.0) - (wholeSt?.expectancyR ?: 0.0)))

    println("─── جدول ماه‌ها ───")
    periodic.summaryRows.forEach { println("  $it") }
}
