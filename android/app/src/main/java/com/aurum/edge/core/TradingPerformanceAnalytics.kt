package com.aurum.edge.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * موتور خالص «کارنامهٔ عملکرد» ژورنال (بدون هیچ وابستگی اندرویدی، کاملاً قابل تست).
 *
 * این موتور همان شاخص‌هایی را می‌سازد که یک tear-sheet حرفه‌ای نشان می‌دهد — منحنی سرمایه،
 * حداکثر افت سرمایه، امید ریاضی به R، نسبت پاداش/ریسک، توزیع R، بهترین/بدترین نماد و
 * تفکیک بر اساس دستهٔ بازار و تایم‌فریم — تا کارنامهٔ کلی ژورنال با «بهترین متود» بازطراحی شود.
 */
object TradingPerformanceAnalytics {

    /** یک نقطه روی منحنی سرمایه. */
    data class EquityPoint(
        val index: Int,
        val atMs: Long,
        val cumulativePnlUsd: Double,
        val cumulativeR: Double,
        val drawdownUsd: Double,
    )

    /** عملکرد یک گروه (نماد/دسته/تایم‌فریم/استراتژی). */
    data class GroupPerformance(
        val key: String,
        val labelFa: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val netPnlUsd: Double,
        val totalR: Double,
        val winRatePct: Double,
        val profitFactor: Double?,
        val expectancyR: Double,
        val contributionPct: Double,
    )

    /** روشن (توزیع) R برای هیستوگرام. */
    data class RDistribution(
        val bucketsFa: List<String>,
        val counts: List<Int>,
        val maxCount: Int,
    )

    /** گزارش کامل کارنامه. */
    data class PerformanceReport(
        val closedTrades: Int,
        val openTrades: Int,
        val netPnlUsd: Double,
        val grossProfitUsd: Double,
        val grossLossUsd: Double,
        val totalR: Double,
        val avgRPerTrade: Double,
        val winRatePct: Double,
        val profitFactor: Double?,
        val payoffRatio: Double?,
        val expectancyUsdPerTrade: Double,
        val avgWinUsd: Double,
        val avgLossUsd: Double,
        val avgWinR: Double,
        val avgLossR: Double,
        val bestTradeUsd: Double,
        val worstTradeUsd: Double,
        val bestTradeR: Double,
        val worstTradeR: Double,
        val avgHoldMinutes: Double,
        val maxConsecutiveWins: Int,
        val maxConsecutiveLosses: Int,
        val currentStreak: Int,
        val maxDrawdownUsd: Double,
        val maxDrawdownPctOfPeak: Double,
        val recoveryFactor: Double?,
        val equityCurve: List<EquityPoint>,
        val byAssetClass: List<GroupPerformance>,
        val bySymbol: List<GroupPerformance>,
        val byTimeframe: List<GroupPerformance>,
        val byDirection: List<GroupPerformance>,
        val rDistribution: RDistribution,
        val last7DaysPnlUsd: Double,
        val last30DaysPnlUsd: Double,
        val headlineFa: String,
        val healthVerdictFa: String,
        val healthColorKey: String,
    )

    // ───────────────────────────────── ورودی‌ها ─────────────────────────────────

    private fun realizedR(trade: PaperTrade): Double {
        trade.rMultiple?.let { if (it.isFinite()) return it }
        val pnl = trade.pnlUsd ?: 0.0
        val risk = trade.riskUsd.takeIf { it.isFinite() && it > 0.0 } ?: 5.0
        return (pnl / risk).coerceIn(-50.0, 50.0)
    }

    private fun assetLabelFa(code: String): String = when (code) {
        AssetClass.CRYPTO.code -> "رمزارز"
        AssetClass.COMMODITY.code -> "طلا و نقره"
        AssetClass.FOREX.code -> "فارکس"
        AssetClass.STOCK.code -> "شاخص/سهام"
        else -> code
    }

    private fun r2(v: Double): Double = round(v * 100.0) / 100.0

    // ───────────────────────────────── محاسبهٔ اصلی ─────────────────────────────────

    fun analyze(
        trades: List<PaperTrade>,
        nowMs: Long = System.currentTimeMillis(),
        startingBalanceUsd: Double = 0.0,
    ): PerformanceReport {
        val open = trades.count { it.isOpen }
        val closed = trades
            .filter { !it.isOpen && it.pnlUsd != null }
            .sortedBy { it.closedAt ?: it.openedAt }

        if (closed.isEmpty()) {
            return PerformanceReport(
                closedTrades = 0,
                openTrades = open,
                netPnlUsd = 0.0,
                grossProfitUsd = 0.0,
                grossLossUsd = 0.0,
                totalR = 0.0,
                avgRPerTrade = 0.0,
                winRatePct = 0.0,
                profitFactor = null,
                payoffRatio = null,
                expectancyUsdPerTrade = 0.0,
                avgWinUsd = 0.0,
                avgLossUsd = 0.0,
                avgWinR = 0.0,
                avgLossR = 0.0,
                bestTradeUsd = 0.0,
                worstTradeUsd = 0.0,
                bestTradeR = 0.0,
                worstTradeR = 0.0,
                avgHoldMinutes = 0.0,
                maxConsecutiveWins = 0,
                maxConsecutiveLosses = 0,
                currentStreak = 0,
                maxDrawdownUsd = 0.0,
                maxDrawdownPctOfPeak = 0.0,
                recoveryFactor = null,
                equityCurve = emptyList(),
                byAssetClass = emptyList(),
                bySymbol = emptyList(),
                byTimeframe = emptyList(),
                byDirection = emptyList(),
                rDistribution = RDistribution(emptyList(), emptyList(), 0),
                last7DaysPnlUsd = 0.0,
                last30DaysPnlUsd = 0.0,
                headlineFa = "هنوز معاملهٔ بسته‌شده‌ای برای کارنامه وجود ندارد",
                healthVerdictFa = "برای داوری عملکرد حداقل چند معاملهٔ بسته‌شده لازم است.",
                healthColorKey = "NEUTRAL",
            )
        }

        val pnls = closed.map { it.pnlUsd ?: 0.0 }
        val rs = closed.map { realizedR(it) }
        val net = pnls.sum()
        val grossProfit = pnls.filter { it > 0.0 }.sum()
        val grossLoss = abs(pnls.filter { it < 0.0 }.sum())
        val winIdx = pnls.indices.filter { pnls[it] > 0.0 }
        val lossIdx = pnls.indices.filter { pnls[it] < 0.0 }
        val wins = winIdx.size
        val losses = lossIdx.size
        val winRate = if (closed.isNotEmpty()) wins.toDouble() / closed.size * 100.0 else 0.0
        val avgWin = if (wins > 0) winIdx.sumOf { pnls[it] } / wins else 0.0
        val avgLoss = if (losses > 0) lossIdx.sumOf { pnls[it] } / losses else 0.0
        val avgWinR = if (wins > 0) winIdx.sumOf { rs[it] } / wins else 0.0
        val avgLossR = if (losses > 0) lossIdx.sumOf { rs[it] } / losses else 0.0
        val profitFactor = if (grossLoss > 1e-9) grossProfit / grossLoss else null
        val payoff = if (abs(avgLoss) > 1e-9) avgWin / abs(avgLoss) else null
        val totalR = rs.sum()

        // منحنی سرمایه و حداکثر افت
        var cumulative = 0.0
        var cumulativeR = 0.0
        var peak = 0.0
        var maxDd = 0.0
        var maxDdPct = 0.0
        val curve = ArrayList<EquityPoint>(closed.size)
        closed.forEachIndexed { index, trade ->
            val pnl = trade.pnlUsd ?: 0.0
            cumulative += pnl
            cumulativeR += rs[index]
            peak = max(peak, cumulative)
            val dd = cumulative - peak
            maxDd = min(maxDd, dd)
            val baseForPct = if (startingBalanceUsd > 0.0) startingBalanceUsd + peak else peak
            if (baseForPct > 1e-9) maxDdPct = max(maxDdPct, abs(dd) / baseForPct * 100.0)
            curve += EquityPoint(
                index = index,
                atMs = trade.closedAt ?: trade.openedAt,
                cumulativePnlUsd = r2(cumulative),
                cumulativeR = r2(cumulativeR),
                drawdownUsd = r2(dd),
            )
        }

        // رشته‌های برد/باخت
        var maxWinStreak = 0
        var maxLossStreak = 0
        var run = 0
        for (pnl in pnls) {
            if (pnl > 0.0) {
                run = if (run > 0) run + 1 else 1
                maxWinStreak = max(maxWinStreak, run)
            } else if (pnl < 0.0) {
                run = if (run < 0) run - 1 else -1
                maxLossStreak = max(maxLossStreak, -run)
            } else {
                run = 0
            }
        }
        val lastPnl = pnls.lastOrNull() ?: 0.0
        val currentStreak: Int = if (lastPnl == 0.0) {
            0
        } else {
            var streak = 0
            for (i in pnls.indices.reversed()) {
                val p = pnls[i]
                val sameSign = if (lastPnl > 0.0) p > 0.0 else p < 0.0
                if (!sameSign) break
                streak++
            }
            if (lastPnl > 0.0) streak else -streak
        }

        val holdMinutes = closed.mapNotNull { t ->
            val end = t.closedAt ?: return@mapNotNull null
            max(0L, end - t.openedAt).toDouble() / 60_000.0
        }
        val avgHold = if (holdMinutes.isNotEmpty()) holdMinutes.average() else 0.0

        val last7 = closed.filter { (it.closedAt ?: 0L) >= nowMs - 7L * 24 * 3600_000 }.sumOf { it.pnlUsd ?: 0.0 }
        val last30 = closed.filter { (it.closedAt ?: 0L) >= nowMs - 30L * 24 * 3600_000 }.sumOf { it.pnlUsd ?: 0.0 }

        val byAsset = group(closed, net) { listOf(it.assetClass.code to assetLabelFa(it.assetClass.code)) }
        val bySymbol = group(closed, net) { listOf(V1Universe.normalize(it.symbol) to V1Universe.normalize(it.symbol)) }
            .sortedByDescending { it.netPnlUsd }
        val byTf = group(closed, net) { listOf(it.interval.label to it.interval.label) }
        val byDirection = group(closed, net) {
            val code = if (it.action == com.aurum.edge.domain.SignalAction.BUY) "LONG" else "SHORT"
            listOf(code to if (code == "LONG") "خرید (LONG)" else "فروش (SHORT)")
        }

        val health = healthVerdict(winRate, profitFactor, net, payoff)

        return PerformanceReport(
            closedTrades = closed.size,
            openTrades = open,
            netPnlUsd = r2(net),
            grossProfitUsd = r2(grossProfit),
            grossLossUsd = r2(grossLoss),
            totalR = r2(totalR),
            avgRPerTrade = r2(if (closed.isNotEmpty()) totalR / closed.size else 0.0),
            winRatePct = r2(winRate),
            profitFactor = profitFactor?.let { r2(it) },
            payoffRatio = payoff?.let { r2(it) },
            expectancyUsdPerTrade = r2(net / closed.size),
            avgWinUsd = r2(avgWin),
            avgLossUsd = r2(avgLoss),
            avgWinR = r2(avgWinR),
            avgLossR = r2(avgLossR),
            bestTradeUsd = r2(pnls.maxOrNull() ?: 0.0),
            worstTradeUsd = r2(pnls.minOrNull() ?: 0.0),
            bestTradeR = r2(rs.maxOrNull() ?: 0.0),
            worstTradeR = r2(rs.minOrNull() ?: 0.0),
            avgHoldMinutes = r2(avgHold),
            maxConsecutiveWins = maxWinStreak,
            maxConsecutiveLosses = maxLossStreak,
            currentStreak = currentStreak,
            maxDrawdownUsd = r2(abs(maxDd)),
            maxDrawdownPctOfPeak = r2(maxDdPct),
            recoveryFactor = if (abs(maxDd) > 1e-9) r2(net / abs(maxDd)) else null,
            equityCurve = curve,
            byAssetClass = byAsset,
            bySymbol = bySymbol,
            byTimeframe = byTf,
            byDirection = byDirection,
            rDistribution = rDistribution(rs),
            last7DaysPnlUsd = r2(last7),
            last30DaysPnlUsd = r2(last30),
            headlineFa = headline(closed.size, net, totalR, winRate),
            healthVerdictFa = health.first,
            healthColorKey = health.second,
        )
    }

    private fun group(
        closed: List<PaperTrade>,
        netTotal: Double,
        keyOf: (PaperTrade) -> List<Pair<String, String>>,
    ): List<GroupPerformance> {
        val buckets = LinkedHashMap<String, MutableList<PaperTrade>>()
        val labels = HashMap<String, String>()
        for (trade in closed) {
            for ((key, label) in keyOf(trade)) {
                labels[key] = label
                buckets.getOrPut(key) { mutableListOf() }.add(trade)
            }
        }
        return buckets.map { (key, list) ->
            val pnls = list.map { it.pnlUsd ?: 0.0 }
            val rs = list.map { realizedR(it) }
            val gp = pnls.filter { it > 0.0 }.sum()
            val gl = abs(pnls.filter { it < 0.0 }.sum())
            GroupPerformance(
                key = key,
                labelFa = labels[key] ?: key,
                trades = list.size,
                wins = pnls.count { it > 0.0 },
                losses = pnls.count { it < 0.0 },
                netPnlUsd = r2(pnls.sum()),
                totalR = r2(rs.sum()),
                winRatePct = r2(if (list.isNotEmpty()) pnls.count { it > 0.0 }.toDouble() / list.size * 100.0 else 0.0),
                profitFactor = if (gl > 1e-9) r2(gp / gl) else null,
                expectancyR = r2(if (list.isNotEmpty()) rs.sum() / list.size else 0.0),
                contributionPct = if (abs(netTotal) > 1e-9) r2(pnls.sum() / netTotal * 100.0) else 0.0,
            )
        }.sortedByDescending { it.netPnlUsd }
    }

    private fun rDistribution(rs: List<Double>): RDistribution {
        val labels = listOf("≤ -1R", "-1R…0", "0…1R", "1R…2R", "2R…3R", "≥ 3R")
        val counts = IntArray(labels.size)
        for (r in rs) {
            val idx = when {
                r <= -1.0 -> 0
                r < 0.0 -> 1
                r < 1.0 -> 2
                r < 2.0 -> 3
                r < 3.0 -> 4
                else -> 5
            }
            counts[idx]++
        }
        return RDistribution(labels, counts.toList(), counts.maxOrNull() ?: 0)
    }

    private fun healthVerdict(
        winRatePct: Double,
        profitFactor: Double?,
        net: Double,
        payoff: Double?,
    ): Pair<String, String> {
        val pf = profitFactor ?: 0.0
        val text = when {
            net > 0.0 && pf >= 1.5 && winRatePct >= 45.0 ->
                "✅ سیستم سودده و پایدار: پرافیت فاکتور ${String.format(Locale.US, "%.2f", pf)} " +
                    "و نرخ برد ${String.format(Locale.US, "%.1f%%", winRatePct)} در محدودهٔ سالم است."
            net > 0.0 && pf >= 1.1 ->
                "🟡 سودده اما شکننده: پرافیت فاکتور ${String.format(Locale.US, "%.2f", pf)} نزدیک مرز ۱ است؛ " +
                    "کاهش ریسک هر معامله و سخت‌گیری بیشتر در فیلتر ورود توصیه می‌شود."
            net > 0.0 ->
                "🟠 سود خالص مثبت با پرافیت فاکتور ضعیف (${String.format(Locale.US, "%.2f", pf)}): " +
                    "سود از چند معاملهٔ بزرگ آمده؛ احتمال بازگشت بالاست."
            net < 0.0 && (payoff ?: 0.0) < 1.0 ->
                "🔴 سیستم زیان‌ده با نسبت پاداش/ریسک کمتر از ۱: قبل از افزایش تعداد معامله، نسبت R:R و " +
                    "کیفیت پلن حد ضرر/حد سود باید اصلاح شود."
            else ->
                "🔴 زیان خالص: با فیلتر شبکهٔ عصبی (پس از ۱۰٬۰۰۰ معامله) و بازنگری تایم‌فریم‌های ضعیف، " +
                    "نرخ برد و امید ریاضی باید بهبود یابد."
        }
        val color = when {
            net > 0.0 && pf >= 1.5 -> "GOOD"
            net > 0.0 -> "WARN"
            else -> "BAD"
        }
        return text to color
    }

    private fun headline(count: Int, net: Double, totalR: Double, winRate: Double): String =
        "$count معاملهٔ بسته‌شده · سود خالص ${if (net >= 0) "+" else "−"}" +
            String.format(Locale.US, "$%,.2f", abs(net)) +
            " · مجموع ${String.format(Locale.US, "%+.2fR", totalR)}" +
            " · نرخ برد ${String.format(Locale.US, "%.1f%%", winRate)}"

    /** ضریب شارپ ساده‌شده بر مبنای R معاملات (بدون نرخ بدون‌ریسک) برای نمایش در کارنامه. */
    fun sharpeOfR(rs: List<Double>): Double {
        if (rs.size < 2) return 0.0
        val mean = rs.average()
        val variance = rs.sumOf { (it - mean) * (it - mean) } / (rs.size - 1)
        val sd = sqrt(max(variance, 0.0))
        return if (sd < 1e-9) 0.0 else r2(mean / sd * sqrt(rs.size.toDouble()))
    }
}
