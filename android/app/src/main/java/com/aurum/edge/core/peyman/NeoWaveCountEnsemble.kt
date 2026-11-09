package com.aurum.edge.core.peyman

import com.aurum.edge.core.NeoWaveEngine
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.max

/**
 * # شمارش اصلی و جایگزین نئوویو
 *
 * قانون‌های این لایه:
 * - نئوویو **جهت، ساختار و نقطهٔ ابطال** می‌دهد، نه مجوز ورود.
 * - شمارش با Hard Breach حذف می‌شود (قانون نیلی)؛ نه اینکه با درصد احتمال پوشانده شود.
 * - اگر شمارش اصلی و جایگزین مخالف باشند، امتیاز ساختار پایین می‌آید و ورود Watch می‌شود.
 * - هیچ «احتمال برد» کالیبره‌نشده‌ای تولید نمی‌شود.
 */
data class NeoWaveCountCandidate(
    val id: String,
    val direction: SignalAction,
    val invalidationPrice: Double?,
    val targets: List<Double>,
    val structureScore: Double,
    val rulesPassed: Int,
    val rulesTotal: Int,
    val confidencePct: Double,
    val isPrimary: Boolean,
    val noteFa: String,
)

data class NeoWaveCountEnsembleResult(
    val primary: NeoWaveCountCandidate?,
    val alternatives: List<NeoWaveCountCandidate>,
    val directionalAgreement: Boolean,
    val agreement: NeoWaveEnsembleAgreement,
    val scoreOutOf25: Double,
    val invalidationMapFa: String,
)

object NeoWaveCountEnsemble {

    /** دامنهٔ زیگزاگ بر اساس رژیم نوسان (همه از دادهٔ گذشته). */
    fun amplitudeFor(regime: CryptoMarketRegime, atrPercentile: Double, config: PeymanCryptoConfig): Double =
        when {
            regime == CryptoMarketRegime.ILLIQUID_SHOCK -> config.zigzagAmpHighVol
            atrPercentile <= 30.0 -> config.zigzagAmpLowVol
            atrPercentile >= 70.0 -> config.zigzagAmpHighVol
            else -> config.zigzagAmpNormal
        }

    fun build(
        strategyBars: List<Candle>,
        triggerBars: List<Candle>,
        regime: CryptoMarketRegime,
        atrPercentile: Double,
        config: PeymanCryptoConfig,
    ): NeoWaveCountEnsembleResult {
        val closed = PeymanMath.closed(strategyBars)
        val baseAmp = amplitudeFor(regime, atrPercentile, config)
        val variants = linkedMapOf(
            "primary" to (closed to baseAmp),
            "alternative-narrow" to (closed to max(0.20, baseAmp * 0.75)),
            "alternative-wide" to (closed to baseAmp * 1.30),
            "trigger-tf" to (PeymanMath.closed(triggerBars) to max(0.20, baseAmp * 0.60)),
        )

        val candidates = ArrayList<NeoWaveCountCandidate>()
        for ((id, pair) in variants) {
            val (bars, amp) = pair
            val verdict = try {
                NeoWaveEngine.analyze(bars, amp)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (verdict.hardBreach) continue
            if (verdict.pattern == NeoWaveEngine.NeoWavePattern.UNKNOWN) continue
            val score = structureScore(verdict.rulesPassed, verdict.rulesTotal, verdict.confidencePct, verdict.isConfirmed)
            candidates.add(
                NeoWaveCountCandidate(
                    id = id,
                    direction = verdict.expectedDirection,
                    invalidationPrice = verdict.invalidationPrice,
                    targets = listOfNotNull(verdict.nextTarget),
                    structureScore = score,
                    rulesPassed = verdict.rulesPassed,
                    rulesTotal = verdict.rulesTotal,
                    confidencePct = verdict.confidencePct,
                    isPrimary = id == "primary",
                    noteFa = verdict.summaryFa,
                ),
            )
        }

        if (candidates.isEmpty()) {
            return NeoWaveCountEnsembleResult(
                null, emptyList(), false, NeoWaveEnsembleAgreement.NONE, 0.0,
                "هیچ شمارش معتبری وجود ندارد.",
            )
        }

        val sorted = candidates.sortedByDescending { it.structureScore }
        val primary = sorted.firstOrNull { it.isPrimary } ?: sorted.first()
        val alternatives = sorted.filter { it !== primary }.take(3)

        val directions = (listOfNotNull(primary) + alternatives).map { it.direction }.distinct()
        val agreement = when {
            alternatives.isEmpty() -> NeoWaveEnsembleAgreement.SINGLE
            directions.size == 1 -> NeoWaveEnsembleAgreement.AGREE
            else -> NeoWaveEnsembleAgreement.CONFLICT
        }

        // امتیاز ۰..۲۵: کیفیت شمارش اصلی + پاداش هم‌جهتی جایگزین‌ها، بدون درصد احتمال.
        //
        // کالیبراسیون: سقف هر مؤلفه باید واقعاً دست‌یافتنی باشد، وگرنه آستانه‌های ۷۵/۸۰/۸۵
        // فقط روی کاغذ وجود دارند. اندازه‌گیری روی سری ساختگی نشان داد ضریب ۰٫۷ سقف عملی
        // این مؤلفه را به ~۱۹ می‌رساند و ستاپ سالم را زیر آستانه می‌برد؛ پس وزن کامل داده شد.
        var score = primary.structureScore
        if (agreement == NeoWaveEnsembleAgreement.AGREE) score += 3.0
        if (agreement == NeoWaveEnsembleAgreement.CONFLICT) score -= 8.0
        score = score.coerceIn(0.0, 25.0)

        val invalidationMap = buildString {
            append("ابطال اصلی: ")
            append(primary.invalidationPrice?.let { PeymanMath.faDigits("%.2f".format(java.util.Locale.US, it)) } ?: "نامشخص")
            alternatives.forEach { alt ->
                append(" · جایگزین ${alt.id}: ")
                append(alt.invalidationPrice?.let { PeymanMath.faDigits("%.2f".format(java.util.Locale.US, it)) } ?: "نامشخص")
            }
        }

        return NeoWaveCountEnsembleResult(
            primary = primary,
            alternatives = alternatives,
            directionalAgreement = directions.size == 1,
            agreement = agreement,
            scoreOutOf25 = score,
            invalidationMapFa = invalidationMap,
        )
    }

    private fun structureScore(passed: Int, total: Int, confidence: Double, confirmed: Boolean): Double {
        val t = total.coerceAtLeast(1)
        val rulePart = (passed.toDouble() / t).coerceIn(0.0, 1.0) * 18.0
        val confPart = (confidence.coerceIn(0.0, 100.0) / 100.0) * 4.0
        val confirmPart = if (confirmed) 3.0 else 0.0
        return (rulePart + confPart + confirmPart).coerceIn(0.0, 25.0)
    }
}
