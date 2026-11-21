package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction

/**
 * «دلیل برد و دلیل باخت» برای هر معاملهٔ بسته‌شده.
 *
 * خواستهٔ کاربر:
 *
 * > «یک چیزی که باید هوش عصبی اضافه کنه اینه که دلیل هر برد و ضرر رو باید بنویسه.»
 *
 * این فایل همان کار را می‌کند، ولی با یک قاعدهٔ مشخص: **هیچ جملهٔ ساختگی و هیچ حدس بی‌داده**.
 * هر جمله از یک عدد واقعی می‌آید که یا روی خودِ معامله ثبت شده (`state*`، لحظهٔ ورود) یا از
 * کندل‌های بین ورود و خروج حساب می‌شود (MFE / MAE / تعداد کندل). اگر داده‌ای نبود، همان جمله
 * نوشته نمی‌شود.
 *
 * دو خروجی دارد:
 *
 * ۱) [explain] برای یک معامله: تیتر + فهرست دلیل‌های فارسی + برچسب‌های قابل شمارش.
 * ۲) [aggregate] برای کل ژورنال: کدام ویژگی بیشترین همراهی را با باخت (و با برد) دارد.
 */
object TradeOutcomeExplainer {

    /** برچسب‌های شناخته‌شده؛ همین‌ها در شمارش الگوها استفاده می‌شوند. */
    object Tags {
        const val HEAVY_KUMO = "HEAVY_KUMO"
        const val KUMO_ENTRY = "KUMO_ENTRY"
        const val LATE_ENTRY = "LATE_ENTRY"
        const val FAR_FROM_KIJUN = "FAR_FROM_KIJUN"
        const val CHOP = "CHOP"
        const val THIN_VOLUME = "THIN_VOLUME"
        const val GAVE_BACK_PROFIT = "GAVE_BACK_PROFIT"
        const val NEVER_IN_PROFIT = "NEVER_IN_PROFIT"
        const val LEFT_RUNNER = "LEFT_RUNNER"
        const val CLEAN_TREND = "CLEAN_TREND"
        const val FAST_WIN = "FAST_WIN"
        const val SLOW_BLEED = "SLOW_BLEED"

        /** عنوان فارسی هر برچسب برای صفحهٔ ژورنال و هوش عصبی. */
        fun titleFa(tag: String): String = when (tag) {
            HEAVY_KUMO -> "ورود با ابر ضخیم"
            KUMO_ENTRY -> "ورود داخل/چسبیده به ابر"
            LATE_ENTRY -> "ورود دیرهنگام (دور از کیه‌جین)"
            FAR_FROM_KIJUN -> "فاصلهٔ زیاد از کیه‌جین"
            CHOP -> "بازار بی‌روند (ER پایین)"
            THIN_VOLUME -> "حجم زیر میانگین"
            GAVE_BACK_PROFIT -> "سود رفت و برگشت"
            NEVER_IN_PROFIT -> "هیچ‌وقت در سود نبود"
            LEFT_RUNNER -> "هدف قبل از ادامهٔ حرکت بسته شد"
            CLEAN_TREND -> "روند تمیز (ER بالا)"
            FAST_WIN -> "برد سریع"
            SLOW_BLEED -> "باخت کشدار"
            else -> tag
        }
    }

    /** نتیجهٔ توضیح یک معامله. */
    data class Explanation(
        val headlineFa: String,
        val reasonsFa: List<String>,
        val tags: List<String>,
        val mfeR: Double,
        val maeR: Double,
        val barsHeld: Int,
        val finalR: Double,
    ) {
        /** متن کامل برای ذخیره روی معامله و نمایش در ژورنال. */
        fun textFa(): String = buildString {
            append(headlineFa)
            reasonsFa.forEach { append("\n• ").append(it) }
        }
    }

    /** آمار یک الگو در کل ژورنال. */
    data class PatternStat(
        val tag: String,
        val titleFa: String,
        val wins: Int,
        val losses: Int,
        val winRatePct: Double,
        val netPnlUsd: Double,
    ) {
        val samples: Int get() = wins + losses
    }

    /**
     * توضیح یک معاملهٔ بسته.
     *
     * @param trade معاملهٔ بسته‌شده
     * @param candles کندل‌های تایم‌فریم همان معامله (از ورود تا خروج). خالی = فقط داده‌های خود معامله.
     */
    fun explain(trade: PaperTrade, candles: List<Candle> = emptyList()): Explanation {
        val direction = if (trade.action == SignalAction.SELL) -1.0 else 1.0
        val exit = trade.exitPrice ?: trade.entry
        val risk = (trade.entry - trade.stopLoss) * direction
        val finalR = if (risk > 1e-9) ((exit - trade.entry) * direction) / risk else 0.0
        val pnl = trade.pnlUsd ?: 0.0
        val isWin = pnl >= 0.0

        // ── مسیر قیمت بین ورود و خروج (MFE / MAE) ────────────────────────────────
        var mfeR = -1.0
        var maeR = -1.0
        var barsHeld = 0
        val openedAt = trade.openedAt
        val closedAt = trade.closedAt ?: 0L
        if (candles.isNotEmpty() && risk > 1e-9) {
            val window = candles.filter { it.time in openedAt..(if (closedAt > 0) closedAt else Long.MAX_VALUE) }
            val usable = window
            if (usable.isNotEmpty()) {
                barsHeld = usable.size
                var best = 0.0
                var worst = 0.0
                for (c in usable) {
                    val favorable = if (direction > 0) (c.high - trade.entry) else (trade.entry - c.low)
                    val adverse = if (direction > 0) (trade.entry - c.low) else (c.high - trade.entry)
                    if (favorable > best) best = favorable
                    if (adverse > worst) worst = adverse
                }
                mfeR = best / risk
                maeR = worst / risk
            }
        }

        val tags = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        // ۱) تیتر: نتیجه + خروج + مسیر
        val exitReasonFa = trade.exitReason?.takeIf { it.isNotBlank() } ?: "بسته شد"
        val headline = buildString {
            append(if (isWin) "برد" else "باخت")
            append(" · ").append(if (pnl >= 0) "+" else "−")
            append("%.2f".format(java.util.Locale.US, kotlin.math.abs(pnl))).append(" دلار · ")
            append("%.2f".format(java.util.Locale.US, finalR)).append("R · ")
            append(exitReasonFa)
            if (barsHeld > 0) append(" · ").append(barsHeld).append(" کندل")
        }

        // ۲) مسیر قیمت
        if (mfeR >= 0.0) {
            reasons += "بیشترین سود شناور " + f(mfeR) + "R بود و بیشترین ضرر شناور " + f(maeR) + "R؛ " +
                when {
                    isWin && mfeR - finalR >= 0.8 -> "بیشینهٔ ثبت‌شده " +
                        f(mfeR - finalR) + "R بالاتر از خروج بود؛ ترتیب حرکت در کندل مشخص نیست."
                    isWin -> "این اعداد نوسان ثبت‌شده را نشان می‌دهند، نه علت قطعی برد."
                    mfeR >= 0.8 -> "بیشینهٔ ثبت‌شده مثبت بود ولی نتیجه منفی شد؛ ترتیب دقیق در هر کندل مشخص نیست."
                    else -> "سود شناور ثبت‌شده محدود بود؛ ترتیب دقیق حرکت مشخص نیست."
                }
        }
        if (!isWin && mfeR >= 0.0 && mfeR < 0.2) tags += Tags.NEVER_IN_PROFIT
        if (!isWin && mfeR >= 0.8 && finalR < 0.0) tags += Tags.GAVE_BACK_PROFIT
        if (isWin && mfeR >= 0.0 && mfeR - finalR >= 1.0) tags += Tags.LEFT_RUNNER

        // ۳) وضعیت ابر در لحظهٔ ورود (روی معامله ذخیره شده است)
        val kumoThickness = trade.stateKumoThicknessAtr
        if (kumoThickness >= 0.0) {
            if (kumoThickness >= 0.8) {
                tags += Tags.HEAVY_KUMO
                if (!isWin) {
                    reasons += "ابر لحظهٔ ورود ضخیم بود (" + f(kumoThickness) + " ATR)؛ این ویژگی به‌تنهایی علت باخت را ثابت نمی‌کند."
                }
            }
            if (kumoThickness in 0.0..0.25) {
                tags += Tags.KUMO_ENTRY
                if (isWin) reasons += "ابر هنگام ورود نازک بود (" + f(kumoThickness) + " ATR)."
            }
        }

        // ۴) فاصله از کیه‌جین = دیرآمدن
        val distKijun = trade.stateDistKijunAtr
        if (distKijun >= 0.0) {
            if (distKijun >= 1.5) {
                tags += Tags.FAR_FROM_KIJUN
                tags += Tags.LATE_ENTRY
                reasons += "فاصلهٔ ورود از کیه‌جین " + f(distKijun) + " ATR بود؛ برچسب ورود دیرهنگام برای بررسی."
            } else if (distKijun <= 0.6) {
                reasons += "ورود نزدیک کیه‌جین (" + f(distKijun) + " ATR) بود؛ نقطهٔ ورود متعارف."
            }
        }

        // ۵) روند و نوسان
        val er = trade.stateEr14
        if (er >= 0.0) {
            if (er < 0.25) {
                tags += Tags.CHOP
                reasons += "کارایی روند (ER14) در لحظهٔ ورود " + f(er) + " بود؛ بازار بی‌روند و بازگشتی بود."
            } else if (er >= 0.45) {
                tags += Tags.CLEAN_TREND
                if (isWin) reasons += "ER14 برابر " + f(er) + " بود؛ کارایی حرکت بالا ثبت شد."
            }
        }
        val atrPct = trade.stateAtrPctOfPrice
        if (atrPct >= 0.0) {
            reasons += "نوسان لحظهٔ ورود " + f(atrPct) + "٪ قیمت (ATR) بود."
        }

        // ۶) حجم
        val vol = trade.stateVolumeRatio20
        if (vol >= 0.0 && vol < 0.8) {
            tags += Tags.THIN_VOLUME
            reasons += "حجم " + f(vol) + " برابر میانگین ۲۰ کندل بود."
        }

        // ۷) زمان
        if (barsHeld > 0) {
            if (isWin && barsHeld <= 4) {
                tags += Tags.FAST_WIN
                reasons += "برد در " + barsHeld + " کندل آمد؛ سیگنال تازه و بدون تأخیر بود."
            }
            if (!isWin && barsHeld >= 20) {
                tags += Tags.SLOW_BLEED
                reasons += "باخت " + barsHeld + " کندل طول کشید؛ علت آن از این داده به‌تنهایی مشخص نیست."
            }
        }

        // ۸) یک جملهٔ نتیجه‌گیری که مستقیم به رفتار بعدی وصل است
        val verdict = when {
            !isWin && Tags.HEAVY_KUMO in tags && Tags.FAR_FROM_KIJUN in tags ->
                "درس: در همین وضعیت (ابر ضخیم + ورود دور از کیه‌جین) این استراتژی باخت داده است؛ " +
                    "دورهٔ خنک‌شدن بعد از بسته شدن دقیقاً برای همین حالت اضافه شده است."
            !isWin && Tags.CHOP in tags ->
                "درس: ER14 زیر ۰٫۲۵ یعنی بازار روند ندارد؛ سیگنال‌های شکست در چنین بازاری برمی‌گردند."
            isWin && Tags.LEFT_RUNNER in tags ->
                "درس: اختلاف بیشینه و خروج ارزش بررسی دارد؛ ترتیب حرکت در کندل مشخص نیست."
            isWin -> "درس: همین ترکیب وضعیت، برد داده است."
            else -> "درس: مسیر معامله ثبت شد تا در آمار همان وضعیت شمرده شود."
        }
        reasons += verdict

        return Explanation(
            headlineFa = headline,
            reasonsFa = reasons,
            tags = tags.distinct(),
            mfeR = mfeR,
            maeR = maeR,
            barsHeld = barsHeld,
            finalR = finalR,
        )
    }

    /**
     * شمارش الگوها روی کل ژورنال: کدام برچسب بیشتر با باخت (و با برد) همراه بوده است.
     *
     * فقط معامله‌های واقعی و بسته شمرده می‌شوند؛ ردیف بک‌تست و پوزیشن باز کنار می‌روند.
     */
    fun aggregate(trades: List<PaperTrade>, minSamples: Int = 3): List<PatternStat> {
        val closed = trades.filter { !it.isOpen && !it.isBacktest && it.pnlUsd != null }
        if (closed.isEmpty()) return emptyList()
        val stats = LinkedHashMap<String, Triple<Int, Int, Double>>()
        for (trade in closed) {
            val tags = tagsOf(trade)
            if (tags.isEmpty()) continue
            val win = (trade.pnlUsd ?: 0.0) >= 0.0
            for (tag in tags) {
                val current = stats[tag] ?: Triple(0, 0, 0.0)
                stats[tag] = Triple(
                    current.first + if (win) 1 else 0,
                    current.second + if (win) 0 else 1,
                    current.third + (trade.pnlUsd ?: 0.0),
                )
            }
        }
        return stats.map { (tag, value) ->
            val wins = value.first
            val losses = value.second
            val samples = wins + losses
            PatternStat(
                tag = tag,
                titleFa = Tags.titleFa(tag),
                wins = wins,
                losses = losses,
                winRatePct = if (samples > 0) wins * 100.0 / samples else 0.0,
                netPnlUsd = value.third,
            )
        }
            .filter { it.samples >= minSamples }
            .sortedWith(compareByDescending<PatternStat> { it.losses - it.wins }.thenBy { it.netPnlUsd })
    }

    /**
     * برچسب‌های یک معاملهٔ **بسته** بدون نیاز به کندل: یا از متن ذخیره‌شده روی معامله خوانده
     * می‌شوند (اگر هنگام بسته شدن توضیح ساخته شده باشد) یا از وضعیت ثبت‌شدهٔ ورود بازسازی می‌شوند.
     */
    fun tagsOf(trade: PaperTrade): List<String> {
        if (trade.outcomeTags.isNotEmpty()) return trade.outcomeTags
        val tags = mutableListOf<String>()
        if (trade.stateKumoThicknessAtr >= 0.8) tags += Tags.HEAVY_KUMO
        if (trade.stateDistKijunAtr >= 1.5) tags += Tags.LATE_ENTRY
        if (trade.stateEr14 in 0.0..0.25) tags += Tags.CHOP
        if (trade.stateEr14 >= 0.45) tags += Tags.CLEAN_TREND
        if (trade.stateVolumeRatio20 in 0.0..0.8) tags += Tags.THIN_VOLUME
        if (trade.mfeR >= 0.0 && (trade.pnlUsd ?: 0.0) < 0.0) {
            if (trade.mfeR < 0.2) tags += Tags.NEVER_IN_PROFIT
            if (trade.mfeR >= 0.8) tags += Tags.GAVE_BACK_PROFIT
        }
        return tags
    }

    /** خلاصهٔ فارسی الگوها برای نمایش در صفحهٔ هوش عصبی. */
    fun summaryFa(trades: List<PaperTrade>, minSamples: Int = 3): String {
        val stats = aggregate(trades, minSamples)
        if (stats.isEmpty()) {
            return "برای نوشتن دلیل برد/باخت به حداقل $minSamples معاملهٔ بسته و توضیح‌داده‌شده نیاز است."
        }
        val worst = stats.filter { it.losses > it.wins }.take(3)
        val best = stats.filter { it.wins > it.losses }.take(2)
        return buildString {
            if (worst.isNotEmpty()) {
                append("بیشترین همراهی با باخت: ")
                append(
                    worst.joinToString("، ") {
                        "${it.titleFa} (${it.losses} باخت از ${it.samples})"
                    },
                )
            }
            if (best.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("بیشترین همراهی با برد: ")
                append(best.joinToString("، ") { "${it.titleFa} (${it.wins} برد از ${it.samples})" })
            }
        }
    }

    private fun f(value: Double): String = "%.2f".format(java.util.Locale.US, value)
}
