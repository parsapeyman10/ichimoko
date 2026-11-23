package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import kotlin.math.sqrt

/** Empirical auxiliary learner, not a neural-network weight update. Uses entry snapshots only. */
object RatingEvidenceGate {
    const val MIN_SAMPLES = 30
    private const val Z_95 = 1.959963984540054

    data class Decision(val blocked: Boolean, val samples: Int, val wins: Int, val upperWinRate: Double?, val breakEven: Double?, val reasonFa: String)

    private fun alignment(buy: Int, neutral: Int, sell: Int, action: SignalAction): Int? {
        if (buy < 0 || neutral < 0 || sell < 0 || buy + neutral + sell <= 0) return null
        if (action != SignalAction.BUY && action != SignalAction.SELL) return null
        val score = (buy - sell) * if (action == SignalAction.BUY) 1 else -1
        return score.compareTo(0)
    }

    fun evaluate(trades: List<PaperTrade>, symbol: String, strategy: String, action: SignalAction,
                 h1: TechnicalRating?, h4: TechnicalRating?, riskReward: Double): Decision {
        val a = h1?.let { alignment(it.buy, it.neutral, it.sell, action) }
        val b = h4?.let { alignment(it.buy, it.neutral, it.sell, action) }
        if (a == null || b == null || !riskReward.isFinite() || riskReward <= 0.0)
            return Decision(false, 0, 0, null, null, "سنجش بسته‌شدهٔ ۱H/۴H یا R معتبر در دسترس نیست؛ گیت آماری تصمیمی نگرفت.")
        val symbolKey = V1Universe.normalize(symbol)
        val stratKey = BitcoinStrategyModel.normalizeStrategyId(strategy)
        val sample = trades.filter { t ->
            !t.isOpen && !t.isBacktest && !t.techSnapshotRebuilt &&
                t.pnlUsd?.let { it.isFinite() && it != 0.0 } == true &&
                V1Universe.normalize(t.symbol) == symbolKey &&
                BitcoinStrategyModel.normalizeStrategyId(t.strategyId) == stratKey && t.action == action &&
                alignment(t.tech1hBuy, t.tech1hNeutral, t.tech1hSell, action) == a &&
                alignment(t.tech4hBuy, t.tech4hNeutral, t.tech4hSell, action) == b
        }
        val n = sample.size
        val wins = sample.count { (it.pnlUsd ?: 0.0) > 0.0 }
        val breakEven = 1.0 / (1.0 + riskReward)
        if (n < MIN_SAMPLES) return Decision(false, n, wins, null, breakEven,
            "آموزش آماری سنجش: $n نمونهٔ هم‌نماد/استراتژی/جهت؛ حداقل $MIN_SAMPLES لازم است، ورودی مسدود نشد.")
        val p = wins.toDouble() / n
        val z2 = Z_95 * Z_95
        val upper = ((p + z2 / (2 * n) + Z_95 * sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n)).coerceIn(0.0, 1.0)
        val blocked = upper < breakEven
        val reason = "سنجش ۱H/۴H: $wins برد از $n · سقف بازهٔ اطمینان ۹۵٪ ${"%.1f".format(java.util.Locale.US, upper * 100)}٪ · سربه‌سر ${"%.1f".format(java.util.Locale.US, breakEven * 100)}٪؛ " +
            if (blocked) "ورود مسدود شد." else "شواهد کافی برای مسدودسازی نیست."
        return Decision(blocked, n, wins, upper, breakEven, reason)
    }
}
