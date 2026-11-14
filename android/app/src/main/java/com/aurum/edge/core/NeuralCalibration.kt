package com.aurum.edge.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * # سنجش صادقانهٔ شبکهٔ عصبی (کالیبراسیون + اعتبارسنجی بیرون‌نمونه‌ای)
 *
 * «بهترین مدل» با ادعا سنجیده نمی‌شود. سه عدد که واقعاً می‌گویند شبکه چیزی یاد گرفته یا نه:
 *
 *  • **Brier** — میانگین مربع خطای احتمال. مرجع: همان نرخ برد پایه ۰٫۲۵ ⇒ Brier ≈ ۰٫۱۹.
 *  • **Log-Loss** — جریمهٔ اطمینان اشتباه. مرجع: ۰٫۶۹ (سکهٔ بی‌طرف).
 *  • **بیرون‌نمونه (OOS)** — مدل روی ۷۰٪ اولِ **زمانی** آموزش می‌بیند و روی ۳۰٪ آخر
 *    سنجیده می‌شود؛ اگر OOS از درون‌نمونه بدتر نباشد، نشانهٔ بیش‌برازش نیست.
 *
 * قاعدهٔ طراحی: هیچ عددی این‌جا ثابت نیست؛ همه از نقاط واقعی محاسبه می‌شوند و با نمونهٔ
 * ناکافی، گزارش صریحاً «نامعلوم» می‌گوید.
 */
data class NeuralCalibrationPoint(
    val predictedWinProb: Double,
    val won: Boolean,
    /** فاز/رژیم برای گزارش تفکیکی — خالی = نامعلوم. */
    val stateCode: String = "",
    val atMs: Long = 0L,
)

data class NeuralCalibrationRegimeRow(
    val stateCode: String,
    val samples: Int,
    val wins: Int,
    val actualWinRatePct: Double,
    val meanPredictedPct: Double,
    /** میانگین پیش‌بینی منهای نرخ برد واقعی؛ مثبت = خوش‌بینی. */
    val biasPct: Double,
)

data class NeuralCalibrationReport(
    val samples: Int = 0,
    val wins: Int = 0,
    val baseWinRatePct: Double = 0.0,
    val meanPredictedPct: Double = 0.0,
    val brier: Double = Double.NaN,
    /** Brier مدلِ «همیشه نرخ برد پایه» — اگر مدل از این بهتر نباشد، یادگیری‌ای نیست. */
    val brierBaseline: Double = Double.NaN,
    val logLoss: Double = Double.NaN,
    val oosSamples: Int = 0,
    val oosBrier: Double = Double.NaN,
    val accuracyPct: Double = Double.NaN,
    val regimes: List<NeuralCalibrationRegimeRow> = emptyList(),
    val verdictFa: String = "داده‌ای برای سنجش نیست",
) {
    /** Brier مدل منهای Brier پایه؛ منفی یعنی مدل بهتر از «همیشه نرخ برد پایه». */
    val brierGainVsBaseline: Double
        get() = if (brier.isFinite() && brierBaseline.isFinite()) brierBaseline - brier else Double.NaN
}

/** یک فولد ارزیابی گام‌به‌گام: مدل روی گذشته آموزش دیده و روی بازهٔ بعدی سنجیده شده است. */
data class NeuralCalibrationFold(
    val fold: Int,
    val trainSamples: Int,
    val testSamples: Int,
    val brier: Double,
    val brierBaseline: Double,
    val logLoss: Double,
    val baseWinRatePct: Double,
    val meanPredictedPct: Double,
) {
    /** مثبت = این فولد از «همیشه نرخ برد پایه» بهتر بود. */
    val gain: Double
        get() = if (brier.isFinite() && brierBaseline.isFinite()) brierBaseline - brier else Double.NaN
}

/**
 * نتیجهٔ اعتبارسنجی گام‌به‌گام (Walk-Forward).
 *
 * یک تقسیم ۷۰/۳۰ تنها یک شاهد می‌دهد و می‌تواند خوش‌شانس باشد. این‌جا مدل چند بار و هر بار
 * روی «گذشته تا آن لحظه» آموزش می‌بیند و روی بازهٔ بعدی سنجیده می‌شود — شبیه همان کاری که
 * در معاملهٔ واقعی رخ می‌دهد.
 */
data class NeuralWalkForwardReport(
    val folds: List<NeuralCalibrationFold> = emptyList(),
    val usableFolds: Int = 0,
    val winsAgainstBaseline: Int = 0,
    val meanBrier: Double = Double.NaN,
    val meanBrierBaseline: Double = Double.NaN,
    val worstGain: Double = Double.NaN,
    val verdictFa: String = "ارزیابی گام‌به‌گام انجام نشد",
) {
    val meanGain: Double
        get() = if (meanBrier.isFinite() && meanBrierBaseline.isFinite()) meanBrierBaseline - meanBrier else Double.NaN
}

object NeuralCalibration {

    /** حداقل نمونه برای اینکه هر عددی اعلام شود. کمتر از این، «نامعلوم» صادقانه‌تر است. */
    const val MIN_SAMPLES_FOR_METRICS: Int = 20

    /** نسبت داده‌ای که در انتهای بازه (زمانی) برای سنجش بیرون‌نمونه‌ای کنار می‌رود. */
    const val DEFAULT_OOS_FRACTION: Double = 0.30

    fun evaluate(
        points: List<NeuralCalibrationPoint>,
        oosFraction: Double = DEFAULT_OOS_FRACTION,
    ): NeuralCalibrationReport {
        if (points.size < MIN_SAMPLES_FOR_METRICS) {
            return NeuralCalibrationReport(
                samples = points.size,
                wins = points.count { it.won },
                verdictFa = "نمونه کم است (${points.size} < $MIN_SAMPLES_FOR_METRICS)؛ هیچ آماری اعلام نمی‌شود",
            )
        }
        val wins = points.count { it.won }
        val base = wins.toDouble() / points.size
        val brier = points.sumOf { p -> (p.predictedWinProb - if (p.won) 1.0 else 0.0).let { it * it } } / points.size
        val brierBase = points.sumOf { p -> (base - if (p.won) 1.0 else 0.0).let { it * it } } / points.size
        val logLoss = points.sumOf { p ->
            val q = p.predictedWinProb.coerceIn(1e-6, 1 - 1e-6)
            if (p.won) -ln(q) else -ln(1.0 - q)
        } / points.size
        val correct = points.count { p -> (p.predictedWinProb >= 0.5) == p.won }
        val meanPred = points.map { it.predictedWinProb }.average()

        // تقسیم زمانی: نقاط مرتب بر اساس زمان؛ ۳۰٪ آخر = بیرون‌نمونه.
        val ordered = points.sortedBy { it.atMs }
        val cut = (ordered.size * (1.0 - oosFraction.coerceIn(0.05, 0.5))).toInt()
            .coerceIn(1, ordered.size - 1)
        val oos = ordered.drop(cut)
        val oosBrier = if (oos.size >= 5) {
            oos.sumOf { p -> (p.predictedWinProb - if (p.won) 1.0 else 0.0).let { it * it } } / oos.size
        } else {
            Double.NaN
        }

        val regimes = points
            .filter { it.stateCode.isNotBlank() }
            .groupBy { it.stateCode }
            .map { (code, list) ->
                val w = list.count { it.won }
                val actual = w.toDouble() / list.size * 100.0
                val meanP = list.map { it.predictedWinProb }.average() * 100.0
                NeuralCalibrationRegimeRow(
                    stateCode = code,
                    samples = list.size,
                    wins = w,
                    actualWinRatePct = r1(actual),
                    meanPredictedPct = r1(meanP),
                    biasPct = r1(meanP - actual),
                )
            }
            .sortedByDescending { it.samples }

        val gain = brierBase - brier
        val verdict = when {
            gain <= 0.0 -> "مدل از «همیشه نرخ برد پایه» بهتر نیست (Brier ${r3(brier)} vs ${r3(brierBase)}) — یادگیری قابل استناد نیست"
            oosBrier.isFinite() && oosBrier > brier * 1.25 ->
                "درون‌نمونه بهتر از بیرون‌نمونه است (${r3(brier)} → ${r3(oosBrier)}) — نشانهٔ بیش‌برازش"
            gain >= 0.01 -> "مدل بهتر از پایه است (Brier ${r3(brier)} vs ${r3(brierBase)}؛ سود ${r3(gain)})"
            else -> "بهبود کوچک ولی مثبت (سود Brier ${r3(gain)})"
        }
        return NeuralCalibrationReport(
            samples = points.size,
            wins = wins,
            baseWinRatePct = r1(base * 100.0),
            meanPredictedPct = r1(meanPred * 100.0),
            brier = r3(brier),
            brierBaseline = r3(brierBase),
            logLoss = r3(logLoss),
            oosSamples = oos.size,
            oosBrier = r3(oosBrier),
            accuracyPct = r1(correct * 100.0 / points.size),
            regimes = regimes,
            verdictFa = verdict,
        )
    }

    /** متن فارسی خلاصه برای نمایش در اپ. */
    fun summaryFa(report: NeuralCalibrationReport): String {
        if (!report.brier.isFinite()) return "📊 سنجش: ${report.verdictFa}"
        val oos = if (report.oosBrier.isFinite()) String.format(Locale.US, "%.3f", report.oosBrier) else "—"
        return "📊 ${report.samples} نمونه · Brier ${String.format(Locale.US, "%.3f", report.brier)} " +
            "(پایه ${String.format(Locale.US, "%.3f", report.brierBaseline)}) · OOS $oos · " +
            "پیش‌بینی ${String.format(Locale.US, "%.1f", report.meanPredictedPct)}٪ در برابر واقعی " +
            "${String.format(Locale.US, "%.1f", report.baseWinRatePct)}٪ — ${report.verdictFa}"
    }

    /** حداقل نمونهٔ هر فولد برای اینکه Brier آن فولد اعلام شود. */
    const val MIN_FOLD_TEST_SAMPLES: Int = 10

    /**
     * ارزیابی گام‌به‌گام روی نقاط بیرون‌نمونهٔ هر فولد.
     *
     * ورودی، پیش‌بینی‌هایی است که مدلِ «آموزش‌دیده تا انتهای فولد قبل» روی بازهٔ بعدی داده است.
     * هیچ فولدی با نمونهٔ کم در میانگین حساب نمی‌شود و اگر هیچ فولد معتبری نباشد، عددی اعلام نمی‌شود.
     */
    fun evaluateWalkForward(
        foldPoints: List<List<NeuralCalibrationPoint>>,
        minFoldTestSamples: Int = MIN_FOLD_TEST_SAMPLES,
    ): NeuralWalkForwardReport {
        val folds = ArrayList<NeuralCalibrationFold>()
        var totalTest = 0
        var sumBrier = 0.0
        var sumBaseline = 0.0
        foldPoints.forEachIndexed { idx, points ->
            if (points.size < minFoldTestSamples) return@forEachIndexed
            val wins = points.count { it.won }
            val base = wins.toDouble() / points.size
            val brier = points.sumOf { p -> (p.predictedWinProb - if (p.won) 1.0 else 0.0).let { it * it } } / points.size
            val baseline = points.sumOf { p -> (base - if (p.won) 1.0 else 0.0).let { it * it } } / points.size
            val logLoss = points.sumOf { p ->
                val q = p.predictedWinProb.coerceIn(1e-6, 1 - 1e-6)
                if (p.won) -ln(q) else -ln(1.0 - q)
            } / points.size
            folds += NeuralCalibrationFold(
                fold = idx + 1,
                trainSamples = 0,
                testSamples = points.size,
                brier = r3(brier),
                brierBaseline = r3(baseline),
                logLoss = r3(logLoss),
                baseWinRatePct = r1(base * 100.0),
                meanPredictedPct = r1(points.map { it.predictedWinProb }.average() * 100.0),
            )
            totalTest += points.size
            sumBrier += brier * points.size
            sumBaseline += baseline * points.size
        }
        if (folds.isEmpty()) {
            return NeuralWalkForwardReport(
                verdictFa = "هیچ فولدی نمونهٔ کافی برای سنجش نداشت (حداقل $minFoldTestSamples نمونه در هر فولد)",
            )
        }
        val meanBrier = sumBrier / totalTest
        val meanBaseline = sumBaseline / totalTest
        val wins = folds.count { it.gain > 0.0 }
        val worst = folds.minOf { it.gain }
        val gain = meanBaseline - meanBrier
        val verdict = when {
            wins == folds.size && gain >= 0.01 ->
                "در هر ${folds.size} فولد از پایه بهتر بود (میانگین سود Brier ${r3(gain)})"
            wins == folds.size ->
                "در هر ${folds.size} فولد بهتر از پایه بود، ولی سود کوچک است (${r3(gain)})"
            wins == 0 ->
                "در هیچ فولدی از پایه بهتر نبود — یادگیری قابل استناد نیست"
            else ->
                "فقط در $wins فولد از ${folds.size} فولد بهتر از پایه بود؛ نتیجه پایدار نیست " +
                    "(بدترین فولد ${r3(worst)})"
        }
        return NeuralWalkForwardReport(
            folds = folds,
            usableFolds = folds.size,
            winsAgainstBaseline = wins,
            meanBrier = r3(meanBrier),
            meanBrierBaseline = r3(meanBaseline),
            worstGain = r3(worst),
            verdictFa = verdict,
        )
    }

    /** متن فارسی یک‌خطی برای نمایش در اپ. */
    fun summaryFa(report: NeuralWalkForwardReport): String {
        if (report.usableFolds == 0) return "🚶 گام‌به‌گام: ${report.verdictFa}"
        return String.format(
            Locale.US,
            "🚶 گام‌به‌گام (%d فولد): Brier %.3f در برابر پایه %.3f · سود %.3f · بدترین فولد %.3f — %s",
            report.usableFolds,
            report.meanBrier,
            report.meanBrierBaseline,
            report.meanGain,
            report.worstGain,
            report.verdictFa,
        )
    }

    private fun r1(v: Double): Double = if (v.isFinite()) Math.round(v * 10.0) / 10.0 else Double.NaN
    private fun r3(v: Double): Double = if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else Double.NaN

    /** میانگین قدرمطلق اختلاف پیش‌بینی و واقعیت در سبدهای ده‌تایی (ECE). */
    fun expectedCalibrationError(points: List<NeuralCalibrationPoint>, bins: Int = 10): Double {
        if (points.isEmpty()) return Double.NaN
        val b = bins.coerceIn(2, 20)
        var ece = 0.0
        for (i in 0 until b) {
            val lo = i.toDouble() / b
            val hi = (i + 1).toDouble() / b
            val cell = points.filter { it.predictedWinProb >= lo && it.predictedWinProb < hi }
            if (cell.isEmpty()) continue
            val frac = cell.size.toDouble() / points.size
            val meanPred = cell.map { it.predictedWinProb }.average()
            val actual = cell.count { it.won }.toDouble() / cell.size
            ece += frac * abs(meanPred - actual)
        }
        return ece
    }
}
