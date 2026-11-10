import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.BacktestSeries
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.util.Locale

/**
 * چرا بک‌تست روی نمادهای کریپتو **صفر معامله** می‌دهد؟
 *
 * روی XAU/USD همین موتور با همین داده صدها معامله می‌سازد، ولی روی BTC/USD هیچ. «۰ معامله»
 * بدون عدد، حدس است؛ این آزمایشگاه مستقیم همان تابعی را صدا می‌زند که بک‌تست صدا می‌زند
 * (`evaluateBitcoinStrategy`) و می‌شمارد کدام مرحله ورود را می‌بندد:
 *
 *  ۱. آمادگی (`readiness`) اصلاً به SIGNAL_BUY/SELL نمی‌رسد؟
 *  ۲. می‌رسد ولی `suggestedStopLoss` یا `primaryTakeProfit` خالی است؟
 *  ۳. هر دو هستند ولی جهت حد ضرر/سود نسبت به ورود اشتباه است؟
 *
 * استفاده: tools/strategy_lab/run.sh 0 0 diag <csv> [SYMBOL] [bars]
 */
private fun readCsvLocal(path: String): List<Candle> {
    val f = File(path)
    require(f.isFile) { "فایل پیدا نشد: $path" }
    return BacktestCsvReader.parse(raw = f.readText(), fileName = f.name).candles
}

fun main(args: Array<String>) {
    val path = args.getOrNull(2) ?: error("مسیر CSV لازم است")
    val symbol = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "BTC/USD"
    val limit = args.getOrNull(4)?.toIntOrNull() ?: 4_000

    val all = readCsvLocal(path)
    val start = (all.size - limit).coerceAtLeast(0)
    println("نماد=$symbol · کندل=${all.size} · ارزیابی از کندل $start")

    val config = BaseStrategyConfig()
    val model = BitcoinStrategyModel.BASE_ICHIMOKU

    var evaluated = 0
    val readinessCounts = LinkedHashMap<String, Int>()
    var actionable = 0
    var missingStop = 0
    var missingTarget = 0
    var wrongSide = 0
    val actionCounts = LinkedHashMap<String, Int>()
    var printedSamples = 0

    for (i in start until all.size) {
        val window = all.subList((i - 600).coerceAtLeast(0), i + 1)
        val bar = window.last()
        val now = bar.time + 15 * 60_000L
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = model,
            config = config,
            macroCandles = BacktestSeries.resample(window, com.aurum.edge.domain.Interval.H4),
            strategyCandles = BacktestSeries.resample(window, com.aurum.edge.domain.Interval.H1),
            triggerCandles = window,
            livePriceOverride = bar.close,
            nowMillis = now,
            symbolInput = symbol,
        )
        evaluated++
        readinessCounts[report.readiness.name] = (readinessCounts[report.readiness.name] ?: 0) + 1
        actionCounts[report.action.name] = (actionCounts[report.action.name] ?: 0) + 1

        if (report.isActionable) {
            actionable++
        } else if (report.readiness == StrategyReadinessState.SIGNAL_BUY ||
            report.readiness == StrategyReadinessState.SIGNAL_SELL
        ) {
            val entry = report.suggestedEntry
            val stop = report.suggestedStopLoss
            val target = report.primaryTakeProfit
            when {
                stop == null || stop <= 0.0 -> missingStop++
                target == null || target <= 0.0 -> missingTarget++
                else -> {
                    val buy = report.action == SignalAction.BUY
                    val bad = if (buy) !(stop < entry!! && target > entry!!) else !(stop > entry!! && target < entry!!)
                    if (bad) wrongSide++
                }
            }
            if (printedSamples < 6) {
                printedSamples++
                println(
                    String.format(
                        Locale.US,
                        "  نمونهٔ سیگنال: %s · ورود=%s · SL=%s · TP=%s · R:R=%s · شرط‌های رد‌شده=%s",
                        report.action,
                        report.suggestedEntry,
                        report.suggestedStopLoss,
                        report.primaryTakeProfit,
                        report.riskReward,
                        report.conditions.filterNot { it.passed }.map { it.titleFa },
                    ),
                )
            }
        }
    }

    println("ارزیابی=$evaluated · قابل‌اجرا(isActionable)=$actionable")
    println("  آمادگی: $readinessCounts")
    println("  جهت: $actionCounts")
    println("  سیگنالِ ناقص → بدون SL=$missingStop · بدون TP=$missingTarget · SL/TP در سمت غلط=$wrongSide")
    println("  بیشترین شرط‌های ردشده در آخرین کندل: ")
    IchimokuStrategyEngine.evaluateBitcoinStrategy(
        activeModel = model,
        config = config,
        macroCandles = BacktestSeries.resample(all.takeLast(600), com.aurum.edge.domain.Interval.H4),
        strategyCandles = BacktestSeries.resample(all.takeLast(600), com.aurum.edge.domain.Interval.H1),
        triggerCandles = all.takeLast(600),
        livePriceOverride = all.last().close,
        nowMillis = all.last().time + 15 * 60_000L,
        symbolInput = symbol,
    ).conditions.forEach { c -> println("   [${if (c.passed) "✓" else "✗"}] ${c.titleFa} — ${c.detailFa}") }
}
