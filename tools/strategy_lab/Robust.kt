import com.aurum.edge.core.*
import com.aurum.edge.domain.*

/**
 * آزمون پایداری: چند سری مستقل + چند بستر زمانی.
 * یک نتیجهٔ خوب روی یک سری می‌تواند شانس باشد؛ اگر روی ۶ سری مستقل مثبت بماند،
 * احتمال اینکه اثر واقعی باشد بالا می‌رود (و اگر روی یکی منفی شد، همان‌جا گفته می‌شود).
 */
fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 5000
    val seeds = listOf(11L, 23L, 37L, 53L, 71L)
    println("پایداری روی ${seeds.size} سری مستقل · هر سری $bars کندل ۱۵دقیقه‌ای")
    for ((symbol, base) in listOf("XAU/USD" to 2400.0, "EUR/USD" to 1.0850, "BTC/USDT" to 62000.0)) {
        for ((trig, strat, macro) in listOf(
            Triple(Interval.M15, Interval.H1, Interval.H4),
            Triple(Interval.H1, Interval.H4, Interval.D1),
        )) {
            val rows = seeds.map { seed ->
                val series = genSeries(bars, seed, startPrice = base)
                val cfg = BacktestReplayEngine.Config(
                    strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id, symbol = symbol,
                    triggerInterval = trig, strategyInterval = strat, macroInterval = macro,
                    warmupBars = 120, includeHiddenCrossLayer = true,
                )
                val res = BacktestReplayEngine.run(series, cfg)
                val st = res.stats.firstOrNull()
                Triple(res.trades.size, st?.expectancyR ?: 0.0, st?.profitFactor ?: 0.0)
            }
            val positive = rows.count { it.second > 0.0 }
            val totalTrades = rows.sumOf { it.first }
            val avgExp = rows.map { it.second }.average()
            println(String.format(java.util.Locale.US,
                "%-9s %s/%s/%s → سری‌های مثبت %d/%d · معامله=%d · میانگین انتظار=%+.2fR · جزئیات=%s",
                symbol, macro.label, strat.label, trig.label, positive, seeds.size, totalTrades, avgExp,
                rows.joinToString(" ") { String.format(java.util.Locale.US, "%d/%+.2fR/PF%.1f", it.first, it.second, it.third) }))
        }
    }
}
