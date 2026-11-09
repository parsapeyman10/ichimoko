package com.aurum.edge.core.peyman

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # کیفیت اجرا و هزینه
 *
 * R:R بدون کسر هزینه، یک عدد خوش‌بینانه است. این‌جا کارمزد رفت‌وبرگشت، اسپرد و
 * Slippage محافظه‌کارانه از پاداش کم و به ریسک اضافه می‌شوند تا تصمیم با عدد واقعی گرفته شود.
 *
 * نکتهٔ واحدها: همهٔ ورودی‌ها بر حسب **قیمت** هستند؛ درصد کارمزد یک‌بار به قیمت تبدیل می‌شود
 * و بافر شکست هم از قبل بر حسب قیمت است — تبدیل دوباره انجام نمی‌شود.
 */
data class ExecutionQuality(
    val ok: Boolean,
    val costPrice: Double,
    val netRisk: Double,
    val netReward: Double,
    val effectiveRiskReward: Double,
    val obstaclePrice: Double?,
    val obstacleSpaceR: Double?,
    val distanceFromKijunAtr: Double?,
    val liquidityAvailable: Boolean,
    val reasonsFa: List<String>,
) {
    val reasonsJoinedFa: String get() = reasonsFa.joinToString(" ")
}

object CryptoExecutionQualityGate {

    fun evaluate(
        symbol: String,
        direction: SignalAction,
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        atr: Double,
        triggerBars: List<Candle>,
        config: PeymanCryptoConfig,
        minRiskReward: Double = config.minRiskReward,
        requireVolume: Boolean = true,
        /**
         * مانع‌ها فقط از کندل‌های **قبل از** این ایندکس خوانده می‌شوند.
         *
         * چرا؟ سطحی که همین حالا شکسته شده، مقاومت نیست؛ اگر کندل شکست هم در جست‌وجوی
         * مانع بیاید، «سقف خودِ شکست» به‌عنوان مانع نزدیک دیده می‌شود و هر ورود
         * بازآزمایی با «فضای ۰٫۴R» رد می‌شود (اندازه‌گیری‌شده، نه فرضی).
         */
        obstacleBeforeIndex: Int? = null,
    ): ExecutionQuality {
        val isLong = direction == SignalAction.BUY
        val reasons = ArrayList<String>()
        if (!PeymanMath.isFinitePositive(entry) || !PeymanMath.isFinitePositive(atr)) {
            return ExecutionQuality(
                false, 0.0, 0.0, 0.0, 0.0, null, null, null, false,
                listOf("قیمت ورود یا ATR نامعتبر است."),
            )
        }
        val risk = abs(entry - stopLoss)
        val reward = abs(takeProfit - entry)
        if (risk <= 0.0 || reward <= 0.0) {
            return ExecutionQuality(
                false, 0.0, 0.0, 0.0, 0.0, null, null, null, false,
                listOf("حد ضرر یا حد سود نامعتبر است."),
            )
        }

        // هزینهٔ رفت‌وبرگشت + اسپرد + Slippage (همه بر حسب قیمت).
        val feePrice = entry * (config.roundTurnFeePct + config.spreadPct) / 100.0
        val slippagePrice = config.slippageAtrFraction * atr
        val costPrice = feePrice + slippagePrice
        val netRisk = risk + costPrice
        val netReward = reward - costPrice
        val rr = if (netRisk > 0.0) netReward / netRisk else 0.0

        // مانع ساختاری: نزدیک‌ترین سقف/کف تاریخی **قبل از** کندل جاری (بدون نگاه به آینده).
        val closed = PeymanMath.closed(triggerBars)
        val obstacle = if (closed.size >= 10) {
            nearestObstacle(closed, entry, isLong, obstacleBeforeIndex ?: (closed.size - 1), atr)
        } else {
            null
        }
        val spaceR = obstacle?.let { abs(it - entry) / risk } 

        // فاصله از کیجون به‌عنوان سقف تعقیب.
        val kijun = if (closed.size >= 26) {
            val window = closed.subList(closed.size - 26, closed.size)
            (window.maxOf { it.high } + window.minOf { it.low }) / 2.0
        } else {
            null
        }
        val kijunDist = kijun?.let { abs(entry - it) / atr }

        if (rr < minRiskReward) {
            reasons.add(
                "R:R مؤثر پس از هزینه ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, rr))} " +
                    "کمتر از حداقل ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, minRiskReward))} است.",
            )
        }
        if (spaceR != null && spaceR < config.minObstacleSpaceR) {
            reasons.add("فضای تا مانع فقط ${PeymanMath.faDigits("%.1f".format(java.util.Locale.US, spaceR))}R است.")
        }
        if (kijunDist != null && kijunDist > config.maxKijunDistanceAtr) {
            reasons.add(
                "فاصله از کیجون ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, kijunDist))} ATR است؛ تعقیب ممنوع.",
            )
        }
        val liquidity = PeymanMath.volumeAvailable(closed)
        if (requireVolume && !liquidity) {
            reasons.add("دادهٔ حجم/نقدشوندگی معتبر نیست؛ کیفیت اجرا قابل تأیید نیست.")
        }
        // حجم غیرعادی + شدوی مخالف بزرگ = کندل شوک؛ اجرا در آن‌جا بی‌کیفیت است.
        val last = closed.lastOrNull()
        if (last != null) {
            val rvol = PeymanMath.relativeVolume(closed, closed.size - 1)
            val adverseWick = if (isLong) PeymanMath.upperWickRatio(last) else PeymanMath.lowerWickRatio(last)
            if (rvol != null && rvol >= 3.0 && adverseWick >= 0.35) {
                reasons.add("کندل شوک با شدوی مخالف بزرگ؛ کیفیت اجرا پایین است.")
            }
        }

        return ExecutionQuality(
            ok = reasons.isEmpty(),
            costPrice = costPrice,
            netRisk = netRisk,
            netReward = netReward,
            effectiveRiskReward = rr,
            obstaclePrice = obstacle,
            obstacleSpaceR = spaceR,
            distanceFromKijunAtr = kijunDist,
            liquidityAvailable = liquidity,
            reasonsFa = reasons,
        )
    }

    private fun nearestObstacle(
        bars: List<Candle>,
        entry: Double,
        isLong: Boolean,
        beforeIndex: Int,
        atr: Double,
    ): Double? {
        // فقط ۵۰ کندل قبل از کندل جاری و فقط قبل از کندل شکست.
        val to = minOf(beforeIndex - 1, bars.size - 2)
        val from = max(0, to - 50)
        if (to <= from) return null
        // مانع باید حداقل نیم ATR دورتر از ورود باشد، وگرنه نویز است.
        val minGap = 0.5 * atr
        var best: Double? = null
        for (k in from..to) {
            val level = if (isLong) bars[k].high else bars[k].low
            val ahead = if (isLong) level > entry + minGap else level < entry - minGap
            if (!ahead) continue
            best = when {
                best == null -> level
                isLong -> minOf(best, level)
                else -> maxOf(best, level)
            }
        }
        return best
    }
}

/**
 * # ریسک پرتفوی و خوشه‌های هم‌بسته
 *
 * «سه پوزیشن Long روی سه آلت هم‌جهت» سه ریسک مستقل نیست؛ یک ریسک است. این گیت اجازه
 * نمی‌دهد محدودیت با تغییر نام نماد دور زده شود و هیچ مسیر Martingale هم ندارد.
 */
object CryptoPortfolioRiskGate {

    fun evaluate(
        direction: SignalAction,
        openPositions: List<com.aurum.edge.core.PaperTrade>,
        config: PeymanCryptoConfig,
        todayLossPct: Double,
        consecutiveLosses: Int,
        fakeBreakoutsSinceLastWin: Int,
        isReversal: Boolean = false,
    ): PeymanGateResult {
        if (todayLossPct >= config.dailyLossLimitPct) {
            return PeymanGateResult.fail(
                "DAILY_LOSS_LIMIT",
                "سقف ضرر روزانه پر شده است (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, todayLossPct))}٪).",
            )
        }
        if (consecutiveLosses >= config.consecutiveLossCooldown) {
            return PeymanGateResult.fail(
                "LOSS_STREAK_COOLDOWN",
                "پس از ${PeymanMath.faDigits(consecutiveLosses.toString())} ضرر متوالی ورود جدید مجاز نیست.",
            )
        }
        if (fakeBreakoutsSinceLastWin >= 1) {
            return PeymanGateResult.fail(
                "FAKE_BREAKOUT_COOLDOWN",
                "آخرین شکست ناموفق بود؛ تا پایان کول‌داون ورود جدید مجاز نیست.",
            )
        }
        val riskPerTrade = effectiveRiskPct(config, isReversal)
        val cryptoOpen = openPositions.filter { com.aurum.edge.core.AssetClass.of(it.symbol) == com.aurum.edge.core.AssetClass.CRYPTO }
        val cluster = cryptoOpen.filter { it.action == direction }
        val clusterRisk = cluster.size * config.riskPercentPerTrade
        if (clusterRisk + riskPerTrade > config.maxClusterRiskPct) {
            return PeymanGateResult.fail(
                "CLUSTER_RISK",
                "ریسک خوشهٔ هم‌جهت (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, clusterRisk))}٪) اجازهٔ پوزیشن تازه نمی‌دهد.",
            )
        }
        val totalRisk = openPositions.size * config.riskPercentPerTrade
        if (totalRisk + riskPerTrade > config.maxTotalRiskPct) {
            return PeymanGateResult.fail(
                "TOTAL_RISK",
                "مجموع ریسک باز (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, totalRisk))}٪) به سقف رسیده است.",
            )
        }
        return PeymanGateResult.ok("PORTFOLIO_OK", "")
    }

    /** ریسک واقعی هر معامله؛ Reversal حداکثر نصف. */
    fun effectiveRiskPct(config: PeymanCryptoConfig, isReversal: Boolean): Double =
        if (isReversal) config.riskPercentPerTrade * config.reversalRiskFraction.coerceIn(0.1, 1.0)
        else config.riskPercentPerTrade
}
