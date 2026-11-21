package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * # عمق یادگیری: سنجش «همهٔ حالت‌ها» برای برد و باخت
 *
 * شبکهٔ عصبی تا امروز می‌گفت «احتمال برد ۶۲٪». سؤالِ مهم‌تر این بود: **کدام حالت‌ها را دیده
 * و کدام‌ها را ندیده؟** و از همه مهم‌تر: آیا در حالت‌هایی که دیده، به همان «قانونِ» تنظیمات
 * (ریسک ۵ دلار، هدف ۱٫۵R تا ۳R، کارمزد داخل محاسبه) می‌رسد یا نه.
 *
 * این فایل شبکهٔ حالت‌ها (lattice) را صریح می‌کند و هر معاملهٔ بسته‌شده را در یک خانه می‌گذارد:
 *
 *   فاز روند (۴) × سشن (۵) × رژیم نوسان (۳) × دالان R (۳) = **۱۸۰ حالت**
 *
 * و برای هر خانه می‌گوید: چند نمونه، چند برد/باخت، نرخ برد، میانگین R، امید ریاضی، **نرخ برد
 * سربه‌سر** (با میانگین برد و باخت واقعی) و فاصله‌اش تا آن نرخ — یعنی دقیقاً همان عددی که
 * می‌گوید «این حالت به قانون می‌رسد یا نه».
 *
 * نُه قاعدهٔ سنجش:
 *  ۱) نمونه‌ها با **نیم‌عمر** وزن می‌گیرند (پیش‌فرض ۳۰ روز): رفتار بازار عوض می‌شود و معاملهٔ
 *     ۶ ماه پیش نباید مثل معاملهٔ دیروز وزن داشته باشد — همان «فراموشی» که کم بود.
 *  ۲) معامله‌ای که ریسکش معلوم نیست (فیلد `fixedRiskUsd` صفر) کنار گذاشته و **شمرده** می‌شود.
 *  ۳) R هر معامله از پول واقعی می‌آید (`pnl ÷ ریسک`)، پس کارمزد و اسپرد داخلش هست.
 *  ۴) هیچ عددی با نمونهٔ کم اعلام نمی‌شود؛ خانهٔ کم‌نمونه «در حال یادگیری» است، نه «سودده».
 *  ۵) «سربه‌سر» یعنی نرخ برد لازم با میانگین برد/باختِ **همین** خانه.
 *  ۶) کارمزد سنگین (بیش از ۲۵٪ ریسک) خودش یک برچسب است، چون با آن حتی نرخ برد خوب هم می‌بازد.
 *  ۷) خانهٔ اثبات‌شده = وزن نمونه ≥ ۳۰ و امید ریاضی ≥ +۰٫۱۰R.
 *  ۸) خانهٔ بازنده = وزن نمونه ≥ ۱۲ و امید ریاضی < −۰٫۰۵R ⇒ ضریب حجم صفر.
 *  ۹) خروجی همیشه قابل بازرسی است: پوشش، خانه‌های ندیده، و «راننده‌های» برد/باخت که از داده
 *     شمرده شده‌اند (نه متن آمادهٔ ثابت).
 */

/** لوازم «قانون» که از تنظیمات می‌آید و سنجش باید به آن برسد. */
data class NeuralDepthTargets(
    val riskBudgetUsd: Double = 5.0,
    val rewardFloorR: Double = CapitalPolicy.MIN_RR,
    val rewardCeilingR: Double = CapitalPolicy.MAX_RR,
    val dailyLossLimitUsd: Double = 0.0,
) {
    val rewardFloorUsd: Double get() = riskBudgetUsd * rewardFloorR
    val rewardCeilingUsd: Double get() = riskBudgetUsd * rewardCeilingR

    companion object {
        fun from(policy: CapitalPolicy): NeuralDepthTargets = NeuralDepthTargets(
            riskBudgetUsd = policy.riskBudgetUsd,
            rewardFloorR = CapitalPolicy.MIN_RR,
            rewardCeilingR = CapitalPolicy.MAX_RR,
            dailyLossLimitUsd = policy.dailyLossLimitUsd,
        )
    }
}

/** یک نمونهٔ آموزش‌دیده با کلید حالتش. */
data class NeuralDepthSample(
    val phaseCode: String,
    val sessionCode: String,
    val volBand: String,
    val rrBand: String,
    val won: Boolean,
    val rMultiple: Double,
    val feeSharePct: Double,
    val atMs: Long,
    val weight: Double,
)

/** وضعیت شواهد یک خانه. */
enum class NeuralEvidenceLevel { UNSEEN, GATHERING, USABLE, PROVEN }

/** داوری یک خانه در برابر قانون تنظیمات. */
enum class NeuralStateVerdict { UNSEEN, GATHERING, PROFITABLE, WEAK, LOSING, BLOCKED_BY_FEE }

/** یک خانه از شبکهٔ ۱۸۰حالتی. */
data class NeuralStateCell(
    val phaseCode: String,
    val sessionCode: String,
    val volBand: String,
    val rrBand: String,
    val samples: Int,
    val wins: Int,
    val losses: Int,
    val weightedSamples: Double,
    val winRatePct: Double,
    val avgWinR: Double,
    val avgLossR: Double,
    val expectancyR: Double,
    val requiredWinRatePct: Double,
    val gapPct: Double,
    val avgFeeSharePct: Double,
    val evidence: NeuralEvidenceLevel,
    val verdict: NeuralStateVerdict,
    val sizeMultiplier: Double,
) {
    val key: String get() = keyOf(phaseCode, sessionCode, volBand, rrBand)
    val labelFa: String
        get() = neuralPhaseFa(phaseCode) + " · " + neuralSessionFa(sessionCode) + " · " + neuralVolFa(volBand) + " · " + neuralRrFa(rrBand)

    companion object {
        fun keyOf(phase: String, session: String, vol: String, rr: String): String =
            phase.uppercase() + "|" + session.uppercase() + "|" + vol.uppercase() + "|" + rr.uppercase()
    }
}

/** گزارش کامل عمق یادگیری. */
data class NeuralDepthReport(
    val targets: NeuralDepthTargets = NeuralDepthTargets(),
    val halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
    val journalTrades: Int = 0,
    val scoredTrades: Int = 0,
    val skippedWithoutRisk: Int = 0,
    val latticeSize: Int = 0,
    val cellsObserved: Int = 0,
    val coveragePct: Double = 0.0,
    val overallWinRatePct: Double = 0.0,
    val overallExpectancyR: Double = 0.0,
    val avgWinR: Double = 0.0,
    val avgLossR: Double = 0.0,
    val requiredWinRatePct: Double = 0.0,
    val provenCells: Int = 0,
    val weakCells: Int = 0,
    val losingCells: Int = 0,
    val gatheringCells: Int = 0,
    val cells: List<NeuralStateCell> = emptyList(),
    val bestCells: List<NeuralStateCell> = emptyList(),
    val worstCells: List<NeuralStateCell> = emptyList(),
    val unseenExamplesFa: List<String> = emptyList(),
    val winDriversFa: List<String> = emptyList(),
    val lossDriversFa: List<String> = emptyList(),
    val lawProgressFa: List<String> = emptyList(),
    val verdictFa: String = "هنوز معاملهٔ بسته‌شده‌ای برای سنجش نیست",
) {
    /** ضریب حجم پیشنهادی همین حالت برای گیت (۰ = مسدود، ۰٫۵ = نصف، ۱ = کامل). */
    fun sizeMultiplierFor(phase: String, session: String, vol: String, rr: String): Double {
        val target = NeuralStateCell.keyOf(phase, session, vol, rr)
        return cells.firstOrNull { it.key == target }?.sizeMultiplier ?: 1.0
    }

    /** خانهٔ همین حالت، اگر دیده شده باشد. */
    fun cellFor(phase: String, session: String, vol: String, rr: String): NeuralStateCell? {
        val target = NeuralStateCell.keyOf(phase, session, vol, rr)
        return cells.firstOrNull { it.key == target }
    }

    companion object {
        const val DEFAULT_HALF_LIFE_DAYS = 30
        const val DEFAULT_WINDOW_DAYS = 180
    }
}

object NeuralDepthAudit {

    /** آستانهٔ وزنی نمونه: کمتر از این «در حال یادگیری»، نه سودده. */
    const val MIN_EVIDENCE_WEIGHTED: Double = 12.0

    /** آستانهٔ وزنی نمونه برای «اثبات‌شده». */
    const val PROVEN_EVIDENCE_WEIGHTED: Double = 30.0

    /** امید ریاضی لازم برای «سودده». */
    const val PROFITABLE_EXPECTANCY_R: Double = 0.10

    /** امید ریاضی زیر این مقدار = بازنده (با شواهد کافی). */
    const val LOSING_EXPECTANCY_R: Double = -0.05

    /** کارمزد بیش از این درصد ریسک، خودش برچسب جداگانه دارد. */
    const val HEAVY_FEE_SHARE_PCT: Double = 25.0

    val PHASES = listOf("GENESIS", "CORE_EXPANSION", "LATE_EXHAUSTION", "CHOP_RANGE")
    val SESSIONS = listOf("ASIA", "LONDON", "LONDON_NY", "NEW_YORK", "LATE_NY")
    val VOL_BANDS = listOf("LOW", "NORMAL", "HIGH")
    val RR_BANDS = listOf("TIGHT", "MID", "WIDE")

    /** ۴ × ۵ × ۳ × ۳ = ۱۸۰ حالت. */
    val LATTICE_SIZE: Int = PHASES.size * SESSIONS.size * VOL_BANDS.size * RR_BANDS.size

    // ───────────────────────────── برچسب‌گذاری ─────────────────────────────

    fun phaseCodeOf(raw: String): String {
        val code = raw.trim().uppercase()
        return if (PHASES.contains(code)) code else "UNKNOWN"
    }

    fun sessionCodeOf(entryTimeMs: Long): String =
        NeuralStateFeatures.sessionCodeOf(entryTimeMs).ifBlank { "UNKNOWN" }

    /** رژیم نوسان از ATR٪ (همان مرزهای لایهٔ رفتاری: ۰٫۱۵ و ۰٫۳۵). */
    fun volBandOf(atrPct: Double?): String = when {
        atrPct == null || !atrPct.isFinite() || atrPct <= 0.0 -> "UNKNOWN"
        atrPct < 0.15 -> "LOW"
        atrPct <= 0.35 -> "NORMAL"
        else -> "HIGH"
    }

    /** دالان R پلن: تنگ (۱٫۵–۲) · میانه (۲–۲٫۵) · سخاوتمند (۲٫۵–۳). */
    fun rrBandOf(planR: Double?): String = when {
        planR == null || !planR.isFinite() || planR <= 0.0 -> "UNKNOWN"
        planR < 2.0 -> "TIGHT"
        planR < 2.5 -> "MID"
        else -> "WIDE"
    }

    /** نمونهٔ آماده برای سنجش از یک معاملهٔ بسته‌شدهٔ ژورنال. */
    fun sampleOf(trade: PaperTrade, nowMs: Long, halfLifeDays: Int = NeuralDepthReport.DEFAULT_HALF_LIFE_DAYS): NeuralDepthSample? {
        if (trade.isOpen || trade.pnlUsd == null) return null
        val risk = trade.fixedRiskUsd
        if (!risk.isFinite() || risk <= 1e-9) return null
        val pnl = trade.pnlUsd ?: return null
        if (!pnl.isFinite()) return null
        val fee = trade.effectiveCommissionUsd + trade.effectiveSpreadCostUsd
        val feeShare = if (risk > 1e-9) fee / risk * 100.0 else 0.0
        val closedAt = trade.closedAt ?: trade.openedAt
        val ageDays = ((nowMs - closedAt).coerceAtLeast(0L)).toDouble() / 86_400_000.0
        val weight = if (halfLifeDays <= 0) 1.0 else 0.5.pow(ageDays / halfLifeDays.toDouble())
        return NeuralDepthSample(
            phaseCode = phaseCodeOf(trade.stateTrendPhaseCode),
            sessionCode = sessionCodeOf(trade.openedAt),
            volBand = volBandOf(trade.stateAtrPctOfPrice.takeIf { it > 0.0 }),
            rrBand = rrBandOf(trade.riskReward),
            won = pnl > 0.0,
            rMultiple = pnl / risk,
            feeSharePct = feeShare,
            atMs = closedAt,
            weight = weight,
        )
    }

    /**
     * سنجش کامل: همهٔ معامله‌های بسته‌شدهٔ ژورنال → شبکهٔ حالت‌ها → داوری در برابر قانون.
     */
    fun fromTrades(
        trades: List<PaperTrade>,
        targets: NeuralDepthTargets,
        nowMs: Long = System.currentTimeMillis(),
        halfLifeDays: Int = NeuralDepthReport.DEFAULT_HALF_LIFE_DAYS,
        windowDays: Int = NeuralDepthReport.DEFAULT_WINDOW_DAYS,
    ): NeuralDepthReport = evaluate(
        samples = trades.mapNotNull { sampleOf(it, nowMs, halfLifeDays) },
        targets = targets,
        journalTrades = trades.size,
        skippedWithoutRisk = trades.count { trade ->
            !trade.isOpen && trade.pnlUsd != null &&
                (!trade.fixedRiskUsd.isFinite() || trade.fixedRiskUsd <= 1e-9)
        },
        nowMs = nowMs,
        halfLifeDays = halfLifeDays,
        windowDays = windowDays,
    )

    /** همان سنجش، ولی از نمونه‌های آماده — برای تست و بازپخش. */
    fun evaluate(
        samples: List<NeuralDepthSample>,
        targets: NeuralDepthTargets,
        journalTrades: Int = samples.size,
        skippedWithoutRisk: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        halfLifeDays: Int = NeuralDepthReport.DEFAULT_HALF_LIFE_DAYS,
        windowDays: Int = NeuralDepthReport.DEFAULT_WINDOW_DAYS,
    ): NeuralDepthReport {
        val windowMs = windowDays.toLong() * 86_400_000L
        val inWindow = samples.filter { s ->
            s.weight > 0.0 && s.rMultiple.isFinite() && (nowMs - s.atMs) <= windowMs
        }
        if (inWindow.isEmpty()) {
            return NeuralDepthReport(
                targets = targets,
                halfLifeDays = halfLifeDays,
                windowDays = windowDays,
                journalTrades = journalTrades,
                skippedWithoutRisk = skippedWithoutRisk,
                latticeSize = LATTICE_SIZE,
                verdictFa = "هیچ معاملهٔ بسته‌شده‌ای در بازهٔ $windowDays روز اخیر نیست؛ هیچ آماری اعلام نمی‌شود",
            )
        }

        // ── تفکیک برنده/بازنده برای میانگین‌ها ─────────────────────────────────
        val winners = inWindow.filter { it.won }
        val losers = inWindow.filter { !it.won }
        val winWeight = winners.sumOf { it.weight }
        val lossWeight = losers.sumOf { it.weight }
        val totalWeight = winWeight + lossWeight
        val avgWinR = if (winWeight > 1e-9) winners.sumOf { it.rMultiple * it.weight } / winWeight else 0.0
        val avgLossR = if (lossWeight > 1e-9) abs(losers.sumOf { it.rMultiple * it.weight } / lossWeight) else 0.0
        val winRate = if (totalWeight > 1e-9) winWeight / totalWeight else 0.0
        val expectancy = if (totalWeight > 1e-9) {
            (winningSum(winners) + losers.sumOf { it.rMultiple * it.weight }) / totalWeight
        } else {
            0.0
        }
        val requiredWinRate = requiredWinRate(avgWinR, avgLossR)

        // ── خانه‌ها ────────────────────────────────────────────────────────────
        val grouped = inWindow.groupBy {
            NeuralStateCell.keyOf(it.phaseCode, it.sessionCode, it.volBand, it.rrBand)
        }
        val cells = grouped.map { (_, rows) -> buildCell(rows) }
        val observedKeys = grouped.keys
        val coverage = observedKeys.size.toDouble() / LATTICE_SIZE.toDouble() * 100.0

        val failing = cells.filter { it.verdict == NeuralStateVerdict.LOSING }
        val weak = cells.filter { it.verdict == NeuralStateVerdict.WEAK }
        val proven = cells.filter { it.verdict == NeuralStateVerdict.PROFITABLE }
        val gathering = cells.filter { it.evidence == NeuralEvidenceLevel.GATHERING }

        val best = cells.filter { it.evidence != NeuralEvidenceLevel.GATHERING }
            .sortedByDescending { it.expectancyR }
            .take(3)
        val worst = cells.filter { it.evidence != NeuralEvidenceLevel.GATHERING }
            .sortedBy { it.expectancyR }
            .take(3)

        // ── خانه‌های ندیده (نمونهٔ کوتاه برای اینکه کاربر بداند چه چیزی مانده) ──
        val unseen = ArrayList<String>()
        for (phase in PHASES) {
            for (session in SESSIONS) {
                for (vol in VOL_BANDS) {
                    for (rr in RR_BANDS) {
                        val key = NeuralStateCell.keyOf(phase, session, vol, rr)
                        if (!observedKeys.contains(key)) {
                            unseen += neuralPhaseFa(phase) + " · " + neuralSessionFa(session) + " · " + neuralVolFa(vol) + " · " + neuralRrFa(rr)
                            if (unseen.size >= 8) break
                        }
                    }
                    if (unseen.size >= 8) break
                }
                if (unseen.size >= 8) break
            }
            if (unseen.size >= 8) break
        }

        return NeuralDepthReport(
            targets = targets,
            halfLifeDays = halfLifeDays,
            windowDays = windowDays,
            journalTrades = journalTrades,
            scoredTrades = inWindow.size,
            skippedWithoutRisk = skippedWithoutRisk,
            latticeSize = LATTICE_SIZE,
            cellsObserved = observedKeys.size,
            coveragePct = round2(coverage),
            overallWinRatePct = round2(winRate * 100.0),
            overallExpectancyR = round2(expectancy),
            avgWinR = round2(avgWinR),
            avgLossR = round2(avgLossR),
            requiredWinRatePct = round2(requiredWinRate * 100.0),
            provenCells = proven.size,
            weakCells = weak.size,
            losingCells = failing.size,
            gatheringCells = gathering.size,
            cells = cells.sortedByDescending { it.expectancyR },
            bestCells = best,
            worstCells = worst,
            unseenExamplesFa = unseen,
            winDriversFa = driversFa(inWindow.filter { it.won }, won = true),
            lossDriversFa = driversFa(inWindow.filter { !it.won }, won = false),
            lawProgressFa = lawProgress(targets, avgWinR, avgLossR, winRate, requiredWinRate, expectancy),
            verdictFa = verdictFa(
                scored = inWindow.size,
                coveragePct = coverage,
                winRatePct = winRate * 100.0,
                requiredPct = requiredWinRate * 100.0,
                expectancyR = expectancy,
                proven = proven.size,
                weak = weak.size,
                losing = failing.size,
            ),
        )
    }

    /** نرخ برد سربه‌سر از میانگین برد/باخت واقعی (هر دو شامل کارمزد). */
    fun requiredWinRate(avgWinR: Double, avgLossR: Double): Double {
        if (!avgWinR.isFinite() || !avgLossR.isFinite()) return Double.NaN
        if (avgWinR <= 0.0 || avgLossR <= 0.0) return Double.NaN
        return avgLossR / (avgWinR + avgLossR)
    }

    // ───────────────────────────── داخل‌ها ─────────────────────────────

    private fun winningSum(winners: List<NeuralDepthSample>): Double =
        winners.sumOf { it.rMultiple * it.weight }

    private fun buildCell(rows: List<NeuralDepthSample>): NeuralStateCell {
        val first = rows.first()
        val winners = rows.filter { it.won }
        val losers = rows.filter { !it.won }
        val winWeight = winners.sumOf { it.weight }
        val lossWeight = losers.sumOf { it.weight }
        val totalWeight = winWeight + lossWeight
        val avgWin = if (winWeight > 1e-9) winners.sumOf { it.rMultiple * it.weight } / winWeight else 0.0
        val avgLoss = if (lossWeight > 1e-9) abs(losers.sumOf { it.rMultiple * it.weight } / lossWeight) else 0.0
        val winRate = if (totalWeight > 1e-9) winWeight / totalWeight else 0.0
        val expectancy = if (totalWeight > 1e-9) {
            (winners.sumOf { it.rMultiple * it.weight } + losers.sumOf { it.rMultiple * it.weight }) / totalWeight
        } else {
            0.0
        }
        val required = requiredWinRate(avgWin, avgLoss)
        val gap = if (required.isFinite()) (winRate - required) * 100.0 else 0.0
        val avgFee = rows.sumOf { it.feeSharePct } / rows.size
        val evidence = when {
            totalWeight <= 1e-9 -> NeuralEvidenceLevel.UNSEEN
            totalWeight < MIN_EVIDENCE_WEIGHTED -> NeuralEvidenceLevel.GATHERING
            totalWeight < PROVEN_EVIDENCE_WEIGHTED -> NeuralEvidenceLevel.USABLE
            else -> NeuralEvidenceLevel.PROVEN
        }
        // ترتیب مهم است: اول «شواهد کم»، بعد «بازندهٔ گران» (دلیلش کارمزد است)، بعد سودده.
        // حالت پرسود با کارمزد بالا مسدود نمی‌شود، چون R از قبل کارمزد را کسر کرده است.
        val verdict = when {
            evidence == NeuralEvidenceLevel.GATHERING -> NeuralStateVerdict.GATHERING
            expectancy < LOSING_EXPECTANCY_R && avgFee > HEAVY_FEE_SHARE_PCT -> NeuralStateVerdict.BLOCKED_BY_FEE
            expectancy >= PROFITABLE_EXPECTANCY_R && gap >= 0.0 -> NeuralStateVerdict.PROFITABLE
            expectancy < LOSING_EXPECTANCY_R -> NeuralStateVerdict.LOSING
            else -> NeuralStateVerdict.WEAK
        }
        val sizeMultiplier = when (verdict) {
            NeuralStateVerdict.PROFITABLE -> 1.0
            NeuralStateVerdict.GATHERING, NeuralStateVerdict.UNSEEN -> 1.0
            NeuralStateVerdict.WEAK -> 0.5
            NeuralStateVerdict.BLOCKED_BY_FEE, NeuralStateVerdict.LOSING -> 0.0
        }
        return NeuralStateCell(
            phaseCode = first.phaseCode,
            sessionCode = first.sessionCode,
            volBand = first.volBand,
            rrBand = first.rrBand,
            samples = rows.size,
            wins = winners.size,
            losses = losers.size,
            weightedSamples = round2(totalWeight),
            winRatePct = round2(winRate * 100.0),
            avgWinR = round2(avgWin),
            avgLossR = round2(avgLoss),
            expectancyR = round2(expectancy),
            requiredWinRatePct = if (required.isFinite()) round2(required * 100.0) else 0.0,
            gapPct = round2(gap),
            avgFeeSharePct = round2(avgFee),
            evidence = evidence,
            verdict = verdict,
            sizeMultiplier = sizeMultiplier,
        )
    }

    /** «راننده‌های» برد یا باخت — همه از داده شمرده می‌شوند. */
    private fun driversFa(rows: List<NeuralDepthSample>, won: Boolean): List<String> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val total = rows.size

        fun share(count: Int): Int = if (total > 0) ((count.toDouble() / total) * 100.0).toInt() else 0

        val bySession = rows.groupBy { it.sessionCode }.maxByOrNull { it.value.size }
        if (bySession != null && bySession.value.size * 2 >= total) {
            out += neuralSessionFa(bySession.key) + " با " + bySession.value.size + " نمونه (" + share(bySession.value.size) + "٪)"
        }
        val byPhase = rows.groupBy { it.phaseCode }.maxByOrNull { it.value.size }
        if (byPhase != null && byPhase.value.size * 2 >= total) {
            out += neuralPhaseFa(byPhase.key) + " با " + byPhase.value.size + " نمونه (" + share(byPhase.value.size) + "٪)"
        }
        val byVol = rows.groupBy { it.volBand }.maxByOrNull { it.value.size }
        if (byVol != null && byVol.value.size * 2 >= total) {
            out += neuralVolFa(byVol.key) + " با " + byVol.value.size + " نمونه (" + share(byVol.value.size) + "٪)"
        }
        if (!won) {
            val heavyFee = rows.count { it.feeSharePct > HEAVY_FEE_SHARE_PCT }
            if (heavyFee > 0) {
                out += heavyFee.toString() + " باخت با کارمزد بیش از " + HEAVY_FEE_SHARE_PCT.toInt() + "٪ ریسک (" +
                    share(heavyFee) + "٪ از باخت‌ها)"
            }
            val tightR = rows.count { it.rrBand == "TIGHT" }
            if (tightR > 0 && tightR * 2 >= total) {
                out += tightR.toString() + " باخت در هدف‌های تنگ ۱٫۵–۲R (" + share(tightR) + "٪)"
            }
        } else {
            val wideR = rows.count { it.rrBand == "WIDE" }
            if (wideR > 0 && wideR * 2 >= total) {
                out += wideR.toString() + " برد در هدف‌های ۲٫۵–۳R (" + share(wideR) + "٪)"
            }
            val normalVol = rows.count { it.volBand == "NORMAL" }
            if (normalVol > 0 && normalVol * 2 >= total) {
                out += normalVol.toString() + " برد در نوسان معمول (" + share(normalVol) + "٪)"
            }
        }
        return out.take(4)
    }

    /** پیشرفت واقعی به سمت «قانون تنظیمات». */
    private fun lawProgress(
        targets: NeuralDepthTargets,
        avgWinR: Double,
        avgLossR: Double,
        winRate: Double,
        required: Double,
        expectancyR: Double,
    ): List<String> {
        val out = ArrayList<String>()
        out += "قانون: ریسک $" + money(targets.riskBudgetUsd) + " · هدف $" + money(targets.rewardFloorUsd) +
            " تا $" + money(targets.rewardCeilingUsd)
        out += "میانگین برد " + round2(avgWinR) + "R ($" + money(avgWinR * targets.riskBudgetUsd) + ") · میانگین باخت " +
            round2(avgLossR) + "R ($" + money(avgLossR * targets.riskBudgetUsd) + ")"
        if (required.isFinite()) {
            out += "نرخ برد لازم برای سربه‌سر " + round2(required * 100.0) + "٪ · نرخ برد واقعی " +
                round2(winRate * 100.0) + "٪ (" + (if (winRate >= required) "بالاتر از لازم" else "کمتر از لازم") + ")"
        }
        out += "امید ریاضی هر معامله " + (if (expectancyR >= 0) "+" else "") + round2(expectancyR) + "R ($" +
            (if (expectancyR >= 0) "+" else "") + money(expectancyR * targets.riskBudgetUsd) + ")"
        if (targets.dailyLossLimitUsd > 0.0) {
            val capacity = targets.dailyLossLimitUsd / (targets.riskBudgetUsd * avgLossR.coerceAtLeast(0.01))
            out += "ترمز روزانه $" + money(targets.dailyLossLimitUsd) + " جای " + capacity.toInt() + " باخت پشت‌سرهم دارد"
        }
        return out
    }

    private fun verdictFa(
        scored: Int,
        coveragePct: Double,
        winRatePct: Double,
        requiredPct: Double,
        expectancyR: Double,
        proven: Int,
        weak: Int,
        losing: Int,
    ): String {
        val head = when {
            expectancyR >= PROFITABLE_EXPECTANCY_R -> "✅ شبکه در این بازه سودده است"
            expectancyR > 0 -> "⚠️ شبکه در این بازه فقط سربه‌سر است"
            else -> "⛔ شبکه در این بازه زیان‌ده است"
        }
        return head + " · " + scored + " نمونه · نرخ برد " + round2(winRatePct) + "٪ در برابر لازم " +
            round2(requiredPct) + "٪ · پوشش حالت‌ها " + round2(coveragePct) + "٪ · " +
            proven + " حالت اثبات‌شده، " + weak + " ضعیف، " + losing + " بازنده"
    }

    private fun round2(v: Double): Double = if (!v.isFinite()) 0.0 else round(v * 100.0) / 100.0

}

// ── برچسب‌های فارسی حالت‌ها (مشترک بین داده و گزارش) ──────────────────────────

internal fun neuralPhaseFa(code: String): String = when (code.uppercase()) {
    "GENESIS" -> "تولد روند"
    "CORE_EXPANSION" -> "قلب روند"
    "LATE_EXHAUSTION" -> "اشباع روند"
    "CHOP_RANGE" -> "رِنج"
    else -> "نامعلوم"
}

internal fun neuralSessionFa(code: String): String = when (code.uppercase()) {
    "ASIA" -> "آسیا"
    "LONDON" -> "لندن"
    "LONDON_NY" -> "لندن-نیویورک"
    "NEW_YORK" -> "نیویورک"
    "LATE_NY" -> "پایان نیویورک"
    else -> "نامعلوم"
}

internal fun neuralVolFa(code: String): String = when (code.uppercase()) {
    "LOW" -> "نوسان کم"
    "NORMAL" -> "نوسان معمول"
    "HIGH" -> "نوسان بالا"
    else -> "نوسان نامعلوم"
}

internal fun neuralRrFa(code: String): String = when (code.uppercase()) {
    "TIGHT" -> "هدف ۱٫۵–۲R"
    "MID" -> "هدف ۲–۲٫۵R"
    "WIDE" -> "هدف ۲٫۵–۳R"
    else -> "هدف نامعلوم"
}
