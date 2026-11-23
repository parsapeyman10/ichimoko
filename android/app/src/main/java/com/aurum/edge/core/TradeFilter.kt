package com.aurum.edge.core

import java.time.LocalDate
import java.time.ZoneOffset

/** Pure, read-only exploration of the journal. Blank fields mean no restriction. */
object TradeFilter {
    const val UNKNOWN = "__unknown__"
    data class Dimension(val key: String, val titleFa: String, val value: (PaperTrade) -> String)
    data class Numeric(val key: String, val titleFa: String, val value: (PaperTrade) -> Double?)

    private fun rating(buy: Int, neutral: Int, sell: Int): String =
        if (buy < 0 || neutral < 0 || sell < 0 || buy + neutral + sell <= 0) UNKNOWN
        else TechnicalRatingInsight.bucketOf(buy, sell).titleFa
    private fun known(s: String) = s.takeIf { it.isNotBlank() } ?: UNKNOWN
    fun outcome(t: PaperTrade): String = when {
        t.isOpen -> "باز"
        t.pnlUsd == null || !t.pnlUsd.isFinite() -> "بسته؛ نتیجه نامعلوم"
        t.pnlUsd > 0 -> "برد"
        t.pnlUsd < 0 -> "باخت"
        else -> "سربه‌سر"
    }
    val dimensions = listOf(
        Dimension("strategy", "استراتژی") { known(it.strategyId) },
        Dimension("symbol", "نماد") { known(V1Universe.normalize(it.symbol)) },
        Dimension("interval", "تایم‌فریم ورود") { it.interval.label },
        Dimension("outcome", "وضعیت / نتیجه", ::outcome),
        Dimension("direction", "جهت") { it.action.name },
        Dimension("session", "سشن ورود UTC") { if (it.openedAt > 0) SessionPerformanceReport.sessionLabel(NeuralStateFeatures.sessionCodeOf(it.openedAt)) else UNKNOWN },
        Dimension("hour", "ساعت ورود UTC") { if (it.openedAt > 0) "%02d".format(java.util.Locale.US, (it.openedAt / 3_600_000L) % 24) else UNKNOWN },
        Dimension("weekday", "روز ورود UTC") { if (it.openedAt > 0) java.time.Instant.ofEpochMilli(it.openedAt).atZone(ZoneOffset.UTC).dayOfWeek.name else UNKNOWN },
        Dimension("origin", "منبع") { if (it.isBacktest) "بک‌تست" else if (it.origin == PaperTrade.ORIGIN_LIVE) "ژورنال کاغذی" else known(it.origin) },
        Dimension("phase", "فاز روند هنگام ورود") { known(it.stateTrendPhaseCode) },
        Dimension("preentry", "الگوی سه کندل ورود") { known(it.preEntry3CandleCategoryFa.ifBlank { it.preEntry3CandleCategoryCode }) },
        Dimension("tech1h", "سنجش ۱H هنگام ورود") { rating(it.tech1hBuy, it.tech1hNeutral, it.tech1hSell) },
        Dimension("tech4h", "سنجش ۴H هنگام ورود") { rating(it.tech4hBuy, it.tech4hNeutral, it.tech4hSell) },
        Dimension("techsource", "منشأ سنجش") { if (rating(it.tech1hBuy, it.tech1hNeutral, it.tech1hSell) == UNKNOWN && rating(it.tech4hBuy, it.tech4hNeutral, it.tech4hSell) == UNKNOWN) UNKNOWN else if (it.techSnapshotRebuilt) "بازسازی‌شده" else "ثبت هنگام ورود" },
        Dimension("exit", "علت خروج ثبت‌شده") { if (it.isOpen) "باز" else known(it.exitReason.orEmpty()) },
    )
    private fun finite(v: Double?): Double? = v?.takeIf { it.isFinite() }
    private fun state(v: Double): Double? = finite(v)?.takeIf { it >= 0.0 }
    fun realizedR(t: PaperTrade): Double? = if (!t.isOpen && t.fixedRiskUsd.isFinite() && t.fixedRiskUsd > 0) finite(t.pnlUsd)?.div(t.fixedRiskUsd)?.let(::finite) else null
    val numerics = listOf(
        Numeric("entry", "قیمت ورود") { finite(it.entry)?.takeIf { v -> v > 0 } },
        Numeric("sl", "حد ضرر") { finite(it.stopLoss)?.takeIf { v -> v > 0 } },
        Numeric("tp", "حد سود") { finite(it.takeProfit)?.takeIf { v -> v > 0 } },
        Numeric("exitprice", "قیمت خروج") { if (it.isOpen) null else finite(it.exitPrice)?.takeIf { v -> v > 0 } },
        Numeric("leverage", "اهرم") { it.leverage.toDouble().takeIf { v -> v > 0 } },
        Numeric("margin", "مارجین دلار") { finite(it.marginUsd)?.takeIf { v -> v > 0 } },
        Numeric("fees", "هزینه ثبت‌شده دلار") { finite(it.effectiveCommissionUsd + it.effectiveSpreadCostUsd) },
        Numeric("pnl", "سود/زیان خالص دلار") { if (it.isOpen) null else finite(it.pnlUsd) },
        Numeric("realizedR", "نتیجه R") { realizedR(it) },
        Numeric("risk", "ریسک ثبت‌شده دلار") { finite(it.fixedRiskUsd)?.takeIf { r -> r > 0 } },
        Numeric("rr", "نسبت R:R پلن") { finite(it.riskReward)?.takeIf { r -> r > 0 } },
        Numeric("confidence", "اطمینان٪") { state(it.confidence) },
        Numeric("buy1", "رأی خرید ۱H") { it.tech1hBuy.toDouble().takeIf { v -> v >= 0 } },
        Numeric("neutral1", "رأی خنثی ۱H") { it.tech1hNeutral.toDouble().takeIf { v -> v >= 0 } },
        Numeric("sell1", "رأی فروش ۱H") { it.tech1hSell.toDouble().takeIf { v -> v >= 0 } },
        Numeric("buy4", "رأی خرید ۴H") { it.tech4hBuy.toDouble().takeIf { v -> v >= 0 } },
        Numeric("neutral4", "رأی خنثی ۴H") { it.tech4hNeutral.toDouble().takeIf { v -> v >= 0 } },
        Numeric("sell4", "رأی فروش ۴H") { it.tech4hSell.toDouble().takeIf { v -> v >= 0 } },
        Numeric("er", "ER ورود") { state(it.stateEr14) },
        Numeric("atr", "ATR درصد قیمت ورود") { state(it.stateAtrPctOfPrice) },
        Numeric("distance", "فاصله کیجون / ATR") { state(it.stateDistKijunAtr) },
        Numeric("cloud", "ضخامت ابر / ATR") { state(it.stateKumoThicknessAtr) },
        Numeric("volume", "نسبت حجم ورود") { state(it.stateVolumeRatio20) },
        Numeric("mfe", "بیشینه سود شناور R") { state(it.mfeR) },
        Numeric("mae", "بیشینه ضرر شناور R") { state(it.maeR) },
        Numeric("duration", "مدت معامله بسته به ساعت") { if (it.closedAt != null && it.openedAt > 0 && it.closedAt >= it.openedAt) (it.closedAt - it.openedAt) / 3_600_000.0 else null },
    )
    data class Criteria(val choices: Map<String, String> = emptyMap(), val query: String = "", val fromDate: String = "", val toDate: String = "", val ranges: Map<String, Pair<String, String>> = emptyMap())
    private fun latin(raw: String): String = raw.map { c ->
        when (c) { in '۰'..'۹' -> ('0' + (c - '۰')); in '٠'..'٩' -> ('0' + (c - '٠')); else -> c }
    }.joinToString("")
    fun date(raw: String): LocalDate? = try { LocalDate.parse(latin(raw.trim())).takeIf { it.year in 1..9998 } } catch (_: Exception) { null }
    fun number(raw: String): Double? = raw.trim().replace('٫', '.').map { c ->
        when (c) { in '۰'..'۹' -> ('0' + (c - '۰')); in '٠'..'٩' -> ('0' + (c - '٠')); else -> c }
    }.joinToString("").toDoubleOrNull()?.takeIf { it.isFinite() }
    fun validation(c: Criteria): String? {
        val from = if (c.fromDate.isBlank()) null else date(c.fromDate) ?: return "تاریخ شروع را به صورت YYYY-MM-DD وارد کنید."
        val to = if (c.toDate.isBlank()) null else date(c.toDate) ?: return "تاریخ پایان را به صورت YYYY-MM-DD وارد کنید."
        if (from != null && to != null && from > to) return "تاریخ شروع بعد از تاریخ پایان است."
        for ((key, range) in c.ranges) {
            val min = if (range.first.isBlank()) null else number(range.first) ?: return "حداقل ${numerics.firstOrNull { it.key == key }?.titleFa ?: key} معتبر نیست."
            val max = if (range.second.isBlank()) null else number(range.second) ?: return "حداکثر ${numerics.firstOrNull { it.key == key }?.titleFa ?: key} معتبر نیست."
            if (min != null && max != null && min > max) return "حداقل نمی‌تواند از حداکثر بیشتر باشد."
        }
        return null
    }
    fun apply(trades: List<PaperTrade>, c: Criteria): List<PaperTrade> {
        if (validation(c) != null) return emptyList()
        val from = c.fromDate.takeIf { it.isNotBlank() }?.let { date(it)!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val to = c.toDate.takeIf { it.isNotBlank() }?.let { date(it)!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val query = c.query.trim()
        return trades.filter { t ->
            (from == null || t.openedAt >= from) && (to == null || t.openedAt < to) &&
            (query.isEmpty() || listOf(t.id, t.displayTradeNumber.toString(), t.symbol, t.strategyId, t.strategyTitleFa, t.note, t.exitReason.orEmpty(), t.outcomeReasonFa, t.lessonFa, t.outcomeTags.joinToString(" "), t.entryConditions.joinToString(" ") { it.name + " " + it.detail }).any { it.contains(query, ignoreCase = true) }) &&
            dimensions.all { d -> c.choices[d.key]?.let { it == d.value(t) } ?: true } &&
            (c.choices["tag"]?.let { tag -> if (tag == UNKNOWN) t.outcomeTags.isEmpty() else tag in t.outcomeTags } ?: true) &&
            numerics.all { n ->
                val range = c.ranges[n.key]
                if (range == null || (range.first.isBlank() && range.second.isBlank())) true else {
                    val value = n.value(t)
                    value != null && (range.first.isBlank() || value >= number(range.first)!!) && (range.second.isBlank() || value <= number(range.second)!!)
                }
            }
        }
    }
    data class Summary(val open: Int, val closed: Int, val wins: Int, val losses: Int, val breakeven: Int, val unknown: Int, val net: Double, val meanR: Double?, val rCount: Int, val profitFactor: Double?)
    fun summary(trades: List<PaperTrade>): Summary {
        val closed = trades.filter { !it.isOpen }
        val pnl = closed.mapNotNull { finite(it.pnlUsd) }
        val rs = closed.mapNotNull(::realizedR)
        val losses = -pnl.filter { it < 0 }.sum()
        return Summary(trades.count { it.isOpen }, closed.size, pnl.count { it > 0 }, pnl.count { it < 0 }, pnl.count { it == 0.0 }, closed.size - pnl.size,
            pnl.sum(), rs.takeIf { it.isNotEmpty() }?.average(), rs.size, if (losses > 0) pnl.filter { it > 0 }.sum() / losses else null)
    }
}
