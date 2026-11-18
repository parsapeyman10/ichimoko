package com.aurum.edge.core

import com.aurum.edge.domain.Interval

/**
 * گاردِ «بعد از بسته شدن یک معامله، فوراً همان روند را دوباره باز نکن».
 *
 * ## مشکلی که کاربر گزارش کرد
 *
 * > «تو معامله رو بستی و دقیقاً دوباره ادامهٔ اون روند رو باز کردی، این اشتباهه؛ نباید پشت بسته
 * > شدن یک معامله دوباره با همون استراتژی معامله باز کنی. مدیریتش کن، چون همیشه منفی میشه.»
 *
 * و درست است: قاعدهٔ «یک معاملهٔ باز» فقط از پوزیشن **هم‌زمان** جلوگیری می‌کرد. لحظه‌ای که معامله
 * با حد ضرر بسته می‌شد، همان کندل بعدی دوباره همان نماد و همان استراتژی سیگنال می‌داد (چون
 * ساختار و شیب عوض نشده بود) و سیستم دوباره وارد می‌شد؛ نتیجه: چند باخت پشت سر هم روی یک روند
 * که دیگر کار نمی‌کرد.
 *
 * ## قاعده
 *
 * پس از بسته شدن معاملهٔ یک (نماد، استراتژی) یک **پنجرهٔ خنک‌شدن** باز می‌شود:
 *
 * - پایه: ۸ کندل از تایم‌فریم همان معامله، با کف ۳۰ دقیقه و سقف ۸ ساعت
 *   (۵ دقیقه → ۴۰ دقیقه · ۱۵ دقیقه → ۲ ساعت · ۱ ساعت → ۸ ساعت · ۴ ساعت → ۸ ساعت).
 * - اگر معاملهٔ آخر **باخت** بود، پنجره ۱٫۵ برابر می‌شود (سقف ۱۲ ساعت).
 * - اگر **دو باخت پیاپی** روی همان (نماد، استراتژی) بود، دو برابر می‌شود (سقف ۱۲ ساعت).
 *
 * چرا بر حسب کندل و نه ساعت ثابت؟ چون «یک روند دوباره باز شدن» در تایم‌فریم ۵ دقیقه با ۴ ساعت
 * یکی نیست؛ ۸ کندل در هر تایم‌فریم همان «۸ کندل فرصت برای عوض شدن ساختار» است.
 *
 * گارد فقط به (نماد، استراتژی) حساس است: استراتژی دیگر روی همان نماد یا همین استراتژی روی نماد
 * دیگر آزاد است، چون آن‌ها روند تازه‌ای هستند.
 */
object ReentryGuard {

    /** تعداد کندل پایهٔ خنک‌شدن. */
    const val BASE_COOLDOWN_BARS: Int = 8

    /** کف و سقف زمانی پنجره. */
    const val MIN_COOLDOWN_MS: Long = 30L * 60_000L
    const val MAX_COOLDOWN_MS: Long = 8L * 60L * 60_000L
    const val MAX_LOSS_COOLDOWN_MS: Long = 12L * 60L * 60_000L

    /** ضریب پنجره وقتی معاملهٔ آخر باخت بوده است. */
    const val LOSS_MULTIPLIER: Double = 1.5

    /** ضریب پنجره وقتی دو باخت پیاپی بوده است. */
    const val DOUBLE_LOSS_MULTIPLIER: Double = 2.0

    /**
     * نتیجهٔ سنجش گارد.
     *
     * @param blocked آیا ورود تازه ممنوع است؟
     * @param cooldownMs طول پنجرهٔ خنک‌شدن
     * @param remainingMs چقدر از پنجره مانده (۰ = تمام شده)
     * @param reasonFa دلیل فارسی برای نمایش در ژورنال و لاگ
     */
    data class Decision(
        val blocked: Boolean,
        val cooldownMs: Long,
        val remainingMs: Long,
        val reasonFa: String,
    ) {
        companion object {
            val ALLOWED = Decision(false, 0L, 0L, "")
        }
    }

    /** طول پنجرهٔ خنک‌شدن برای یک معاملهٔ بسته‌شده با [interval] و [consecutiveLosses] باخت پیاپی. */
    fun cooldownMs(interval: Interval, consecutiveLosses: Int): Long {
        val baseBars = BASE_COOLDOWN_BARS.toLong() * interval.millis
        val capped = baseBars.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
        return when {
            consecutiveLosses >= 2 -> (capped * DOUBLE_LOSS_MULTIPLIER).toLong().coerceAtMost(MAX_LOSS_COOLDOWN_MS)
            consecutiveLosses == 1 -> (capped * LOSS_MULTIPLIER).toLong().coerceAtMost(MAX_LOSS_COOLDOWN_MS)
            else -> capped
        }
    }

    /**
     * سنجش گارد برای یک (نماد، استراتژی).
     *
     * @param lastClosed آخرین معاملهٔ بستهٔ همین (نماد، استراتژی) — `null` یعنی آزاد
     * @param consecutiveLosses تعداد باخت‌های پیاپی همین (نماد، استراتژی) تا همین لحظه
     * @param now زمان فعلی (میلی‌ثانیه)
     */
    fun evaluate(
        lastClosed: PaperTrade?,
        consecutiveLosses: Int,
        now: Long,
    ): Decision {
        val closedAt = lastClosed?.closedAt ?: return Decision.ALLOWED
        if (closedAt <= 0L) return Decision.ALLOWED
        val interval = lastClosed.interval
        val window = cooldownMs(interval, consecutiveLosses)
        val elapsed = now - closedAt
        if (elapsed >= window) return Decision.ALLOWED
        val remaining = (window - elapsed).coerceAtLeast(0L)
        val minutesLeft = ((remaining + 59_999L) / 60_000L).coerceAtLeast(1L)
        val windowMinutes = ((window + 59_999L) / 60_000L).coerceAtLeast(1L)
        val why = when {
            consecutiveLosses >= 2 -> "دو باخت پیاپی روی همین روند بوده"
            consecutiveLosses == 1 -> "معاملهٔ آخر باخت بود"
            else -> "تازه بسته شده"
        }
        val reason = "⛔ ${lastClosed.displayTradeNumberBadgeFa} (${lastClosed.symbol} · " +
            "${lastClosed.strategyTitleFa}) ${minutesLeft.formatFa()} دقیقهٔ دیگر در دورهٔ خنک‌شدن است " +
            "(${windowMinutes.formatFa()} دقیقه، چون $why)؛ ورود دوباره به همین روند مجاز نیست"
        return Decision(blocked = true, cooldownMs = window, remainingMs = remaining, reasonFa = reason)
    }

    /**
     * شمارش باخت‌های پیاپی همین (نماد، استراتژی) از جدید به قدیم.
     *
     * معاملهٔ باز و ردیف‌های بک‌تست نادیده گرفته می‌شوند؛ فقط نتیجهٔ معامله‌های واقعیِ بسته مهم است.
     */
    fun consecutiveLosses(trades: List<PaperTrade>, symbolKey: String, strategyKey: String): Int {
        val closed = trades
            .asSequence()
            .filter { !it.isOpen && !it.isBacktest }
            .filter { V1Universe.normalize(it.symbol) == symbolKey }
            .filter { BitcoinStrategyModel.normalizeStrategyId(it.strategyId) == strategyKey }
            .sortedByDescending { it.closedAt ?: 0L }
            .toList()
        var count = 0
        for (trade in closed) {
            val pnl = trade.pnlUsd ?: break
            if (pnl < 0.0) count++ else break
        }
        return count
    }

    private fun Long.formatFa(): String {
        val digits = "۰۱۲۳۴۵۶۷۸۹"
        return toString().map { ch -> if (ch.isDigit()) digits[ch - '0'] else ch }.joinToString("")
    }
}
