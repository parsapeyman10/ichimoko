import com.aurum.edge.core.*
import com.aurum.edge.domain.*

/**
 * مقایسهٔ بستر زمانی (تایم‌فریم ماشه/استراتژی/ماکرو) روی همان سری.
 *
 * چرا این مقایسه حیاتی است؟ چون هندسهٔ معامله در ایچیموکو با ATR مقیاس می‌گیرد،
 * اما هزینهٔ اجرا درصدی از قیمت است. در تایم پایین، ۱R کوچک می‌شود و کارمزد
 * بخش بزرگی از ۱R را می‌خورد — چیزی که هیچ فیلتر استراتژی نمی‌تواند جبران کند.
 */
fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 11L
    val series = genSeries(bars, seed)
    val combos = listOf(
        Triple(Interval.M15, Interval.H1, Interval.H4),
        Triple(Interval.M30, Interval.H4, Interval.D1),
        Triple(Interval.H1, Interval.H4, Interval.D1),
    )
    println("سری=${series.size} کندل ۱۵دقیقه‌ای")
    for ((trig, strat, macro) in combos) {
        val cfg = BacktestReplayEngine.Config(
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id, symbol = "BTC/USDT",
            triggerInterval = trig, strategyInterval = strat, macroInterval = macro,
            warmupBars = 120, includeHiddenCrossLayer = true,
        )
        val res = BacktestReplayEngine.run(series, cfg)
        val st = res.stats.firstOrNull()
        val oneRs = res.trades.map { kotlin.math.abs(it.entryPrice - it.stopLoss) / it.entryPrice * 100 }
        val feeR = res.trades.map { it.feeUsd / (kotlin.math.abs(it.entryPrice - it.stopLoss) * (1000.0 * 0.005 / kotlin.math.abs(it.entryPrice - it.stopLoss))) }
        println(String.format(java.util.Locale.US,
            "%s/%s/%s → معامله=%d · برد=%.0f%% · خالص=%+.2f$ · PF=%.2f · انتظار=%+.2fR · میانهٔ ۱R=%.2f%% · میانهٔ کارمزد=%.2fR",
            macro.label, strat.label, trig.label, res.trades.size,
            st?.winRatePct ?: 0.0, st?.netPnlUsd ?: 0.0, st?.profitFactor ?: 0.0, st?.expectancyR ?: 0.0,
            oneRs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] },
            feeR.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }))
    }
}
