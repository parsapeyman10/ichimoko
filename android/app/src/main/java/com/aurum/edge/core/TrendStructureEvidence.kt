package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import java.util.Locale

/** Descriptive snapshot, never a trading veto, stop update, or inferred future result. */
object TrendStructureEvidence {
    data class Values(
        val directional52Atr: Double,
        val tenkanBehindAtr: Double,
        val kijunBehindAtr: Double,
        val tenkanSlope: Double,
        val kijunSlope: Double,
        val tenkanFlat: Boolean,
        val kijunFlat: Boolean,
        val cloudCurrentAtr: Double,
        val cloudProjectedAtr: Double,
        val distanceFromCompressionAtr: Double?,
    ) {
        fun textFa(): String {
            fun f(v: Double) = String.format(Locale.US, "%.2f", v)
            val dist = distanceFromCompressionAtr?.let { f(it) + " ATR از سطح شکست فشردگی ثبت‌شده" }
                ?: "فاصله از فشردگی نامعلوم (شکست معتبر ثبت نشده)"
            return "ساختار کندل بسته: تغییر جهت‌دار ۵۲ کندل ${f(directional52Atr)} ATR؛ فاصلهٔ جهت‌دار قیمت از تنکان ${f(tenkanBehindAtr)} و کیجون ${f(kijunBehindAtr)} ATR؛ " +
                "تنکان ${if (tenkanFlat) "فلت" else "متحرک"} با شیب ${f(tenkanSlope)}، کیجون ${if (kijunFlat) "فلت" else "متحرک"} با شیب ${f(kijunSlope)} ATR/کندل؛ " +
                "ضخامت ابر جاری ${f(cloudCurrentAtr)} و بخش ازپیش‌ترسیم‌شده ${f(cloudProjectedAtr)} ATR؛ $dist. " +
                "این اندازه‌ها علت برخورد استاپ یا ادامهٔ روند را ثابت نمی‌کنند."
        }
    }
    fun extract(report: StrategyEvaluationReport): Values? {
        val s = report.triggerSnapshot ?: return null
        val sign = when (report.action) { SignalAction.BUY -> 1.0; SignalAction.SELL -> -1.0; else -> return null }
        val a = s.atr14
        if (!a.isFinite() || a <= 0 || s.time != report.triggerBarTime ||
            listOf(s.close, s.tenkan, s.kijun, s.currentSpanA, s.currentSpanB, s.futureSpanA, s.futureSpanB,
                s.closeChange52Atr, s.tenkanSlopeAtrPerBar, s.kijunSlopeAtrPerBar).any { !it.isFinite() }) return null
        val expansion = report.compressionExpansion
        val level = expansion?.breakoutLevel?.takeIf { it.isFinite() && it > 0 && expansion.direction == report.action &&
            expansion.breakoutBarTime != null && expansion.breakoutBarTime <= s.time }
        return Values(
            directional52Atr = sign * s.closeChange52Atr,
            tenkanBehindAtr = sign * (s.close - s.tenkan) / a,
            kijunBehindAtr = sign * (s.close - s.kijun) / a,
            tenkanSlope = sign * s.tenkanSlopeAtrPerBar,
            kijunSlope = sign * s.kijunSlopeAtrPerBar,
            tenkanFlat = s.isTenkanFlat, kijunFlat = s.isKijunFlat,
            cloudCurrentAtr = kotlin.math.abs(s.currentSpanA - s.currentSpanB) / a,
            cloudProjectedAtr = kotlin.math.abs(s.futureSpanA - s.futureSpanB) / a,
            distanceFromCompressionAtr = level?.let { sign * (s.close - it) / a },
        )
    }
}
