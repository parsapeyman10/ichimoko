package com.aurum.edge.core

import kotlin.math.round

/**
 * «آزمایشگاه کیفیت ورود» — فاز ۲ از نقشهٔ راه کالیبراسیون.
 *
 * ## چه می‌کند
 * معاملات **بسته‌شدهٔ** ژورنال را بر اساس ویژگی‌های لحظهٔ ورود می‌بُرد و برای هر تکه می‌گوید:
 * چند معامله، نرخ برد، امید ریاضی دلاری و سهم از کل. سپس چند «قاعدهٔ فیلتر» را به‌صورت
 * بازپخش روی همان معاملات می‌سنجد: «اگر فقط این‌ها را می‌گرفتیم، سود کل چه می‌شد؟»
 *
 * ## چه نمی‌کند
 * - هیچ رفتاری را تغییر نمی‌دهد و هیچ معامله‌ای نمی‌سازد.
 * - هیچ عددی را از آیندهٔ معامله در تصمیم ورود استفاده نمی‌کند؛ فقط نتیجهٔ تحقق‌یافته را
 *   برای دسته‌بندی به کار می‌برد.
 * - «درون‌نمونه‌ای» است: روی همان معاملاتی حساب می‌شود که برنامه گرفته است. برای همین گزارش
 *   خودش هشدار می‌دهد که این اعداد باید روی نمونهٔ بعدی هم تأیید شوند.
 *
 * ## چرا این ماژول مهم است
 * اندازه‌گیری فاز ۰ نشان داد جای استاپ محدود است؛ شکاف تا سربه‌سر (تا ۱۴ واحد نرخ برد) فقط با
 * «کم‌کردن ورودهای بد» پر می‌شود. اینجا همان ورودهای بد پیدا می‌شوند.
 */
object EntryQualityLab {

    /** حداقل نمونه برای اینکه یک تکه «قابل‌اتکا» شمرده شود. */
    const val MIN_SAMPLES: Int = 20

    /** حداقلی که یک قاعدهٔ فیلتر باید از معاملات را نگه دارد تا «سیگنال‌کُش» نباشد. */
    const val MIN_KEPT_FOR_RULE: Int = 30

    /** یک معاملهٔ بسته‌شده با ویژگی‌های لحظهٔ ورودش. */
    data class Sample(
        val tradeId: String,
        val strategyId: String,
        val strategyTitleFa: String,
        val symbol: String,
        val intervalLabel: String,
        val openedAt: Long,
        val isBuy: Boolean,
        val pnlUsd: Double,
        val rMultiple: Double,
        /** دستهٔ ۳ کندل قبل از ورود (`CCLAMP_EXTENDED_CHASE` و …). */
        val categoryCode: String,
        val categoryLabelFa: String,
        /** فاصلهٔ قیمت از کیجون در لحظهٔ ورود بر حسب ATR؛ `-1` = نامعلوم. */
        val distKijunAtr: Double,
        /** کارایی روند در لحظهٔ ورود؛ `-1` = نامعلوم. */
        val er14: Double,
        val tech1hBuy: Int,
        val tech1hNeutral: Int,
        val tech1hSell: Int,
        val tech4hBuy: Int,
        val tech4hNeutral: Int,
        val tech4hSell: Int,
    )

    /** یک تکه از داده (مثلاً «۱ ساعته هم‌جهت»). */
    data class Bucket(
        val key: String,
        val labelFa: String,
        val samples: Int,
        val wins: Int,
        val winRatePct: Double,
        val expectancyUsd: Double,
        val totalPnlUsd: Double,
        val sharePct: Double,
    ) {
        val reliable: Boolean get() = samples >= MIN_SAMPLES
    }

    /** نتیجهٔ بازپخش یک قاعدهٔ فیلتر روی همان معاملات. */
    data class RuleResult(
        val id: String,
        val labelFa: String,
        val kept: Int,
        val dropped: Int,
        val keptExpectancyUsd: Double,
        val keptTotalPnlUsd: Double,
        val baselineExpectancyUsd: Double,
        val baselineTotalPnlUsd: Double,
    ) {
        val keptSharePct: Double get() = if (kept + dropped == 0) 0.0 else kept * 100.0 / (kept + dropped)
        val deltaPnlUsd: Double get() = round((keptTotalPnlUsd - baselineTotalPnlUsd) * 100.0) / 100.0
        val expectancyGainUsd: Double get() = round((keptExpectancyUsd - baselineExpectancyUsd) * 100.0) / 100.0

        /** قاعده فقط وقتی «بهتر» است که هم امید ریاضی بالاتر باشد و هم معامله را نکشد. */
        val better: Boolean
            get() = kept >= MIN_KEPT_FOR_RULE && expectancyGainUsd > 0.05
    }

    data class Dimension(val key: String, val titleFa: String, val buckets: List<Bucket>)

    data class Report(
        val dimensions: List<Dimension>,
        val rules: List<RuleResult>,
        val samples: Int,
        val unknownGaugeSamples: Int,
        val days: Int,
        val generatedAt: Long,
    ) {
        val hasData: Boolean get() = samples > 0
        val baselineExpectancyUsd: Double
            get() = dimensions.firstOrNull()?.buckets?.firstOrNull { it.key == "all" }?.expectancyUsd ?: 0.0
    }

    // ─── ابزارهای داخلی ─────────────────────────────────────────────────────────

    private enum class Align(val key: String, val labelFa: String) {
        ALIGNED("aligned", "هم‌جهت با معامله"),
        NEUTRAL("neutral", "خنثی"),
        OPPOSITE("opposite", "مخالف معامله"),
        UNKNOWN("unknown", "بدون داده سنجش"),
    }

    private fun bias(buy: Int, sell: Int): Int = when {
        buy < 0 || sell < 0 -> Int.MIN_VALUE // نامعلوم
        buy > sell -> 1
        sell > buy -> -1
        else -> 0
    }

    private fun alignment(buy: Int, sell: Int, isBuy: Boolean): Align {
        val b = bias(buy, sell)
        if (b == Int.MIN_VALUE) return Align.UNKNOWN
        if (b == 0) return Align.NEUTRAL
        val tradeBias = if (isBuy) 1 else -1
        return if (b == tradeBias) Align.ALIGNED else Align.OPPOSITE
    }

    private fun kijunBucketLabel(dist: Double): String = when {
        !dist.isFinite() || dist < 0.0 -> "نامعلوم"
        dist <= 0.5 -> "≤ ۰٫۵ ATR (چسبیده)"
        dist <= 1.0 -> "۰٫۵ تا ۱ ATR"
        dist <= 1.5 -> "۱ تا ۱٫۵ ATR"
        else -> "> ۱٫۵ ATR (کشیده)"
    }

    private fun sessionBucketLabel(openedAt: Long): String {
        val hour = java.time.Instant.ofEpochMilli(openedAt)
            .atZone(java.time.ZoneOffset.UTC).hour
        return when (hour) {
            in 0..6 -> "آسیا (۰۰–۰۷ UTC)"
            in 7..11 -> "لندن (۰۷–۱۲ UTC)"
            in 12..15 -> "هم‌پوشانی لندن-نیویورک (۱۲–۱۶ UTC)"
            in 16..20 -> "نیویورک (۱۶–۲۱ UTC)"
            else -> "پایان روز (۲۱–۲۴ UTC)"
        }
    }

    private fun buildBuckets(
        samples: List<Sample>,
        keyOf: (Sample) -> String,
        labelOf: (String) -> String = { it },
    ): List<Bucket> {
        if (samples.isEmpty()) return emptyList()
        val total = samples.size
        return samples.groupBy(keyOf).map { (key, group) ->
            val wins = group.count { it.pnlUsd > 0.0 }
            val pnl = group.sumOf { it.pnlUsd }
            Bucket(
                key = key,
                labelFa = labelOf(key),
                samples = group.size,
                wins = wins,
                winRatePct = round(wins * 1000.0 / group.size) / 10.0,
                expectancyUsd = round(pnl / group.size * 100.0) / 100.0,
                totalPnlUsd = round(pnl * 100.0) / 100.0,
                sharePct = round(group.size * 1000.0 / total) / 10.0,
            )
        }.sortedByDescending { it.samples }
    }

    private fun fullBucket(samples: List<Sample>): Bucket? {
        if (samples.isEmpty()) return null
        val wins = samples.count { it.pnlUsd > 0.0 }
        val pnl = samples.sumOf { it.pnlUsd }
        return Bucket(
            key = "all",
            labelFa = "همهٔ معاملات (مبنا)",
            samples = samples.size,
            wins = wins,
            winRatePct = round(wins * 1000.0 / samples.size) / 10.0,
            expectancyUsd = round(pnl / samples.size * 100.0) / 100.0,
            totalPnlUsd = round(pnl * 100.0) / 100.0,
            sharePct = 100.0,
        )
    }

    // ─── قواعد فیلتر ───────────────────────────────────────────────────────────

    private fun ruleResults(samples: List<Sample>): List<RuleResult> {
        if (samples.isEmpty()) return emptyList()
        val baselineExpectancy = samples.sumOf { it.pnlUsd } / samples.size
        val baselineTotal = samples.sumOf { it.pnlUsd }
        fun evaluate(id: String, labelFa: String, predicate: (Sample) -> Boolean): RuleResult {
            val kept = samples.filter(predicate)
            val dropped = samples.size - kept.size
            val keptExpectancy = if (kept.isEmpty()) 0.0 else kept.sumOf { it.pnlUsd } / kept.size
            return RuleResult(
                id = id,
                labelFa = labelFa,
                kept = kept.size,
                dropped = dropped,
                keptExpectancyUsd = round(keptExpectancy * 100.0) / 100.0,
                keptTotalPnlUsd = round(kept.sumOf { it.pnlUsd } * 100.0) / 100.0,
                baselineExpectancyUsd = round(baselineExpectancy * 100.0) / 100.0,
                baselineTotalPnlUsd = round(baselineTotal * 100.0) / 100.0,
            )
        }

        fun h1(s: Sample) = alignment(s.tech1hBuy, s.tech1hSell, s.isBuy)
        fun h4(s: Sample) = alignment(s.tech4hBuy, s.tech4hSell, s.isBuy)

        return listOf(
            evaluate("no_cclamp_chase", "بدون ورود در کشیدگی ۳ کندلی (CCLAMP_EXTENDED_CHASE)") {
                it.categoryCode != PreEntry3CandleCategory.CCLAMP_EXTENDED_CHASE.code
            },
            evaluate("kijun_close", "فقط وقتی قیمت ≤ ۱ ATR از کیجون فاصله دارد") {
                it.distKijunAtr >= 0.0 && it.distKijunAtr <= 1.0
            },
            evaluate("h1_aligned", "فقط وقتی سنجش ۱ ساعته هم‌جهت معامله است") {
                h1(it) == Align.ALIGNED || h1(it) == Align.UNKNOWN
            },
            evaluate("h4_not_opposite", "فقط وقتی سنجش ۴ ساعته مخالف معامله نیست") {
                h4(it) != Align.OPPOSITE
            },
            evaluate("h1h4_aligned", "فقط وقتی ۱ ساعته و ۴ ساعته هر دو هم‌جهت‌اند") {
                (h1(it) == Align.ALIGNED || h1(it) == Align.UNKNOWN) && (h4(it) == Align.ALIGNED || h4(it) == Align.UNKNOWN)
            },
            evaluate("both_aligned_no_chase", "۱ و ۴ ساعته هم‌جهت + بدون کشیدگی ۳ کندلی") {
                (h1(it) == Align.ALIGNED || h1(it) == Align.UNKNOWN) &&
                    (h4(it) == Align.ALIGNED || h4(it) == Align.UNKNOWN) &&
                    it.categoryCode != PreEntry3CandleCategory.CCLAMP_EXTENDED_CHASE.code
            },
            evaluate("both_aligned_near_kijun", "۱ و ۴ ساعته هم‌جهت + فاصله از کیجون ≤ ۱ ATR") {
                (h1(it) == Align.ALIGNED || h1(it) == Align.UNKNOWN) &&
                    (h4(it) == Align.ALIGNED || h4(it) == Align.UNKNOWN) &&
                    it.distKijunAtr >= 0.0 && it.distKijunAtr <= 1.0
            },
            evaluate("london_ny", "فقط سشن لندن و نیویورک") {
                val hour = java.time.Instant.ofEpochMilli(it.openedAt).atZone(java.time.ZoneOffset.UTC).hour
                hour in 7..20
            },
        )
    }

    // ─── تحلیل ─────────────────────────────────────────────────────────────────

    fun analyze(
        samples: List<Sample>,
        days: Int = 30,
        nowMillis: Long = System.currentTimeMillis(),
    ): Report {
        val usable = samples.filter { it.pnlUsd.isFinite() }
        val baseline = fullBucket(usable)
        val dimensions = mutableListOf<Dimension>()

        if (baseline != null) {
            dimensions += Dimension("all", "مبنا", listOf(baseline))
        }
        dimensions += Dimension(
            key = "strategy",
            titleFa = "به تفکیک استراتژی",
            buckets = buildBuckets(usable, { it.strategyTitleFa.ifBlank { it.strategyId } }),
        )
        dimensions += Dimension(
            key = "category",
            titleFa = "دستهٔ ۳ کندل قبل از ورود",
            buckets = buildBuckets(usable, {
                it.categoryLabelFa.ifBlank { it.categoryCode.ifBlank { "نامشخص" } }
            }),
        )
        dimensions += Dimension(
            key = "gauge_1h",
            titleFa = "سنجش ۱ ساعته نسبت به جهت معامله",
            buckets = buildBuckets(
                usable,
                keyOf = { alignment(it.tech1hBuy, it.tech1hSell, it.isBuy).key },
                labelOf = { key -> Align.entries.firstOrNull { it.key == key }?.labelFa ?: key },
            ),
        )
        dimensions += Dimension(
            key = "gauge_4h",
            titleFa = "سنجش ۴ ساعته نسبت به جهت معامله",
            buckets = buildBuckets(
                usable,
                keyOf = { alignment(it.tech4hBuy, it.tech4hSell, it.isBuy).key },
                labelOf = { key -> Align.entries.firstOrNull { it.key == key }?.labelFa ?: key },
            ),
        )
        dimensions += Dimension(
            key = "gauge_both",
            titleFa = "هم‌زمانی ۱ ساعته و ۴ ساعته",
            buckets = buildBuckets(usable, { s ->
                val a = alignment(s.tech1hBuy, s.tech1hSell, s.isBuy)
                val b = alignment(s.tech4hBuy, s.tech4hSell, s.isBuy)
                when {
                    a == Align.UNKNOWN || b == Align.UNKNOWN -> "unknown"
                    a == Align.ALIGNED && b == Align.ALIGNED -> "both_aligned"
                    a == Align.ALIGNED && b == Align.NEUTRAL -> "h1_aligned_h4_neutral"
                    a == Align.NEUTRAL && b == Align.ALIGNED -> "h1_neutral_h4_aligned"
                    a == Align.OPPOSITE && b == Align.OPPOSITE -> "both_opposite"
                    a == Align.ALIGNED -> "h1_aligned_h4_opposite"
                    b == Align.ALIGNED -> "h4_aligned_h1_opposite"
                    else -> "mixed_neutral"
                }
            }, { key ->
                when (key) {
                    "unknown" -> "بدون داده سنجش"
                    "both_aligned" -> "هر دو هم‌جهت ✅"
                    "h1_aligned_h4_neutral" -> "۱ ساعته هم‌جهت، ۴ ساعته خنثی"
                    "h1_neutral_h4_aligned" -> "۴ ساعته هم‌جهت، ۱ ساعته خنثی"
                    "both_opposite" -> "هر دو مخالف ❌"
                    "h1_aligned_h4_opposite" -> "۱ ساعته هم‌جهت، ۴ ساعته مخالف"
                    "h4_aligned_h1_opposite" -> "۴ ساعته هم‌جهت، ۱ ساعته مخالف"
                    else -> "ترکیب خنثی"
                }
            }),
        )
        dimensions += Dimension(
            key = "kijun_distance",
            titleFa = "فاصلهٔ قیمت از کیجون در لحظهٔ ورود",
            buckets = buildBuckets(usable, { kijunBucketLabel(it.distKijunAtr) }),
        )
        dimensions += Dimension(
            key = "session",
            titleFa = "سشن بازار (UTC)",
            buckets = buildBuckets(usable, { sessionBucketLabel(it.openedAt) }),
        )

        val unknownGauge = usable.count {
            bias(it.tech1hBuy, it.tech1hSell) == Int.MIN_VALUE || bias(it.tech4hBuy, it.tech4hSell) == Int.MIN_VALUE
        }
        return Report(
            dimensions = dimensions.filter { it.buckets.isNotEmpty() },
            rules = ruleResults(usable),
            samples = usable.size,
            unknownGaugeSamples = unknownGauge,
            days = days,
            generatedAt = nowMillis,
        )
    }
}
