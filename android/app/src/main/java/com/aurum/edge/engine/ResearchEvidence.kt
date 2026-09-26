package com.aurum.edge.engine

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.FOREX_CALENDAR_SOURCE_URL
import kotlin.math.abs

/** Caution labels, not a statistical test, trading advice or an AI-news verdict. */
enum class EvidenceGrade { NO_DATA, LIMITED, UNFAVORABLE, COST_SENSITIVE, PRELIMINARY }

data class EvidenceAssessment(val grade: EvidenceGrade, val title: String, val detail: String)

data class PaperCostWhatIf(
    val trades: Int,
    val rawPnlUsd: Double,
    val estimatedCostUsd: Double,
    val afterAssumedCostUsd: Double,
    val afterDoubleCostUsd: Double,
)

object ResearchEvidence {
    /** Heuristic caution threshold only. Thirty is NOT statistical significance or a profit guarantee. */
    const val CAUTION_MIN_CLOSED = 30

    fun inSample(result: Backtester.Result): EvidenceAssessment = EvidenceAssessment(
        EvidenceGrade.LIMITED,
        if (result.trades.size < CAUTION_MIN_CLOSED)
            "فقط ${result.trades.size} معاملهٔ بسته؛ نمونهٔ کم"
        else "فقط داخل نمونه؛ شاهدی از سودمندی بیرون از این بازه نیست",
        "بک‌تستِ قواعد فنی، بدون بازپخش گیت نهم خبر AI/ICT/MTF. حداقل ۳۰ فقط هشدار کم‌نمونگی است؛ " +
            "پوزیشن باز انتهای بازه (${if (result.openAtEnd) 1 else 0}) و ${result.unresolvedGap} پوزیشنِ گرفتار در گپ از سود محقق‌شده حذف شده‌اند.",
    )

    fun outOfSample(base: Backtester.Result, stress: Backtester.Result): EvidenceAssessment = when {
        base.bars != stress.bars || base.fromTime != stress.fromTime ||
            base.toTime != stress.toTime || base.symbol != stress.symbol ||
            stress.spreadPrice != base.spreadPrice * 2.0 ||
            stress.commissionPerOz != base.commissionPerOz * 2.0 ->
            mismatchedStress()
        base.unresolvedGap + stress.unresolvedGap > 0 -> gapAssessment()
        else -> assess(base.trades.size, base.netPnl, base.profitFactor,
            stress.trades.size, stress.netPnl, stress.profitFactor,
            base.spreadPrice + 2 * base.commissionPerOz > 0.0)
    }


    /** Ignore old optimistic verdict strings and old close-fill reports on deserialization. */
    fun stored(record: WalkForwardRecord): EvidenceAssessment {
        val base = record.outOfSample
        val stress = record.costStressOutOfSample
        if (stress == null || base.executionModel != Backtester.EXECUTION_MODEL ||
            stress.executionModel != Backtester.EXECUTION_MODEL) {
            return EvidenceAssessment(EvidenceGrade.NO_DATA, "گزارش قدیمی؛ ارزیابی تازه لازم است",
                "این رکورد با مدل ورود کندل بعد و آزمون هزینهٔ دوبرابر ساخته نشده است. " +
                    "جملهٔ قدیمیِ سوددهی/پایداری ملاک نیست؛ دوباره روی کندل‌ها اجرا کنید.")
        }
        val baseline = PerformanceMetrics.fromStoredReport(base)
        val sensitivity = PerformanceMetrics.fromStoredReport(stress)
        if (baseline == null || sensitivity == null ||
            abs(baseline.netPnlUsd - base.netPnl) > 0.01 ||
            abs(sensitivity.netPnlUsd - stress.netPnl) > 0.01) {
            return EvidenceAssessment(EvidenceGrade.NO_DATA, "جزئیات معاملات ذخیره‌شده ناقص است",
                "خالص و تعداد نتایج با فهرست بسته‌ها تطبیق ندارند؛ دوباره روی داده اجرا کنید.")
        }
        if (base.bars != stress.bars || base.fromTime != stress.fromTime ||
            base.toTime != stress.toTime || base.symbol != stress.symbol ||
            stress.spreadPrice != base.spreadPrice * 2.0 ||
            stress.commissionPerOz != base.commissionPerOz * 2.0) return mismatchedStress()
        if (base.unresolvedGap + stress.unresolvedGap > 0) return gapAssessment()
        return assess(baseline.total, baseline.netPnlUsd, baseline.profitFactor,
            sensitivity.total, sensitivity.netPnlUsd, sensitivity.profitFactor,
            base.spreadPrice + 2 * base.commissionPerOz > 0.0)
    }

    private fun mismatchedStress() = EvidenceAssessment(EvidenceGrade.NO_DATA,
        "سناریوی هزینه قابل تطبیق نیست", "بازه، نماد یا فرض هزینهٔ دو گزارش برابر نیست؛ دوباره اجرا کنید.")

    private fun gapAssessment() = EvidenceAssessment(EvidenceGrade.LIMITED,
        "شکاف داده هنگام پوزیشن باز؛ نتیجه نامعین",
        "در نبود کندل‌های میانی معلوم نیست حد سود یا ضرر زده شده باشد؛ معاملات حل‌نشده در P/L نیستند. " +
            "برای داوری، دادهٔ پیوسته‌تر لازم است.")

    private fun assess(count: Int, net: Double, pf: Double?, stressedCount: Int,
                       stressedNet: Double, stressedPf: Double?, costIsNonzero: Boolean): EvidenceAssessment = when {
        count == 0 -> EvidenceAssessment(EvidenceGrade.NO_DATA, "خارج نمونه معاملهٔ بسته‌ای نبود",
            "بازهٔ بزرگ‌ترِ دارای قیمت واقعی لازم است؛ نبود معامله صفر درصد برد نیست.")
        count < CAUTION_MIN_CLOSED -> EvidenceAssessment(EvidenceGrade.LIMITED,
            "فقط $count معاملهٔ بستهٔ خارج نمونه؛ نمونهٔ کم",
            "حداقل ۳۰ فقط آستانهٔ احتیاطی است، نه آزمون معنی‌داری. با این تعداد حتی خالص مثبت شاهد قابل اتکا نیست.")
        !net.isFinite() || net <= 0.0 || (pf != null && pf.isFinite() && pf <= 1.0) ->
            EvidenceAssessment(EvidenceGrade.UNFAVORABLE, "خارج نمونه با فرض فعلی سودمند نبود",
                "خالص و نسبت سود/زیانِ معاملات بسته با هزینهٔ فرضی مثبت و کافی نیستند؛ نتیجهٔ آینده نامعلوم است.")
        pf == null || !pf.isFinite() -> EvidenceAssessment(EvidenceGrade.LIMITED,
            "فاکتور سودِ خارج نمونه تعریف‌پذیر نیست",
            "بدون زیان ثبت‌شده مخرج PF صفر است؛ از نسبت نامعلوم نمی‌توان نتیجهٔ پایداری گرفت.")
        !costIsNonzero -> EvidenceAssessment(EvidenceGrade.LIMITED,
            "هزینه‌های صفر، آزمون حساسیت معنادار نمی‌سازند",
            "اسپرد و کمیسیون واقعی بروکر را وارد کنید؛ لغزش و نقدشوندگی همچنان نامعلوم می‌ماند.")
        stressedCount < CAUTION_MIN_CLOSED -> EvidenceAssessment(EvidenceGrade.LIMITED,
            "با هزینهٔ دوبرابر فقط $stressedCount معاملهٔ بسته ماند",
            "تغییر هزینه می‌تواند ورودها را هم حذف کند؛ این تعداد برای قضاوت اولیه کم است.")
        !stressedNet.isFinite() || stressedNet <= 0.0 ||
            (stressedPf != null && stressedPf.isFinite() && stressedPf <= 1.0) ->
            EvidenceAssessment(EvidenceGrade.COST_SENSITIVE, "نتیجه به هزینهٔ فرضی حساس است",
                "در تکرار همین کندل‌ها با اسپرد و کمیسیون ۲ برابر، خالص/PF مثبت و کافی نماند؛ هزینهٔ واقعی معلوم نیست.")
        stressedPf == null || !stressedPf.isFinite() -> EvidenceAssessment(EvidenceGrade.LIMITED,
            "فاکتور سودِ سناریوی ۲ برابر تعریف‌پذیر نیست",
            "با مخرج صفر/نامعتبر نباید پایداری را نتیجه گرفت؛ بررسی‌های مستقل لازم است.")
        else -> EvidenceAssessment(EvidenceGrade.PRELIMINARY,
            "فقط مشاهدهٔ اولیهٔ تاریخی؛ نه سود قابل اجرا",
            "یک تقسیم زمانی و هزینهٔ فرضی ۲ برابر کافی نیست: گیت AI/ICT تاریخی، لغزش واقعی، " +
                "تغییر رژیم بازار و دادهٔ مستقل بررسی نشده‌اند.")
    }

    /** Recorded evidence, not a fresh verification of a historical publisher or AI model. */
    fun hasRecordedNineWay(trade: PaperTrade): Boolean {
        val bar = trade.signalBarTime ?: return false
        val news = trade.newsEvidence ?: return false
        val ict = trade.priceAction ?: return false
        return trade.symbol == "XAU/USD" && trade.unit == "oz" &&
            trade.action != SignalAction.NO_TRADE && bar > 0L &&
            trade.mtf?.let { !it.veto && it.barTime == bar } == true &&
            ict.symbol == trade.symbol && ict.action == trade.action && ict.barTime == bar &&
            news.model.isNotBlank() && news.model != "deterministic-fallback" &&
            news.direction == trade.action.name && news.confidence in 80.0..100.0 &&
            news.checkedAt > 0L && trade.openedAt - news.checkedAt in 0L..180_000L &&
            trade.openedAt - ict.checkedAt in 0L..180_000L &&
            news.calendarSource == FOREX_CALENDAR_SOURCE_URL &&
            news.calendarCheckedAt?.let { trade.openedAt - it in 0L..1_200_000L } == true &&
            news.evidence.isNotEmpty() && news.evidence.all { it.id.isNotBlank() &&
                it.source.isNotBlank() && it.url.startsWith("https://") &&
                news.checkedAt - it.publishedAt in 0L..10_800_000L } &&
            trade.entryConditions.size == 9 &&
            trade.entryConditions.all { it.status == "CONFIRMED" } &&
            trade.entryConditions.last().name == NewsConfluence.NEWS_LABEL
    }

    /** A hypothetical deduction from recorded paper P/L, not a broker fill or a journal edit. */
    fun paperCostWhatIf(trades: List<PaperTrade>, spread: Double, commissionPerOz: Double): PaperCostWhatIf? {
        if (!spread.isFinite() || spread < 0.0 || !commissionPerOz.isFinite() || commissionPerOz < 0.0) return null
        val settled = trades.filter { hasRecordedNineWay(it) && !it.isOpen && it.pnlUsd?.isFinite() == true &&
            it.positionOz.isFinite() && it.positionOz > 0.0 }
        if (settled.isEmpty()) return null
        val gross = settled.sumOf { it.pnlUsd!! }
        val assumedCost = settled.sumOf { (spread + commissionPerOz * 2.0) * it.positionOz }
        if (!gross.isFinite() || !assumedCost.isFinite()) return null
        return PaperCostWhatIf(settled.size, gross, assumedCost, gross - assumedCost,
            gross - assumedCost * 2.0)
    }
}
