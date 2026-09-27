package com.aurum.edge.core

/**
 * Chart window of an *already recorded* paper trade.
 *
 * Truth rules of this file:
 * - it only SELECTS bars that were already verified as real provider candles (the same cache the
 *   chart uses); it never interpolates, re-times or invents a bar,
 * - a window that the local history does not cover stays [Coverage.MISSING] / [Coverage.PARTIAL]
 *   and says so — an empty chart is a valid, honest answer,
 * - drawing entry/SL/TP of a stored PAPER trade is a record of what was saved, not a claim that a
 *   broker filled it and not a new signal.
 */
object TradeReplay {

    /** How much of the trade the locally available real candles actually cover. */
    enum class Coverage { FULL, PARTIAL, MISSING }

    data class Window(
        val bars: List<Candle> = emptyList(),
        val coverage: Coverage = Coverage.MISSING,
        /** Open time of the bar that contains the recorded entry, when it is on screen. */
        val entryBarTime: Long? = null,
        /** Open time of the bar that contains the recorded exit, when it is on screen. */
        val exitBarTime: Long? = null,
        val requestedFrom: Long = 0L,
        val requestedTo: Long = 0L,
        val detail: String = "",
    ) {
        val hasChart: Boolean get() = bars.size >= 2 && coverage != Coverage.MISSING
    }

    /** Bars kept on each side of the trade so the context around it is visible. */
    const val PADDING_BARS = 18

    /** Upper bound so a long-running position cannot build a huge list for one journal row. */
    const val MAX_BARS = 320

    /** Open time of the bar that contains [at] for [interval] (floor, also for negatives). */
    fun barTimeOf(at: Long, interval: Interval): Long {
        val step = interval.millis
        val remainder = ((at % step) + step) % step
        return at - remainder
    }

    /**
     * Select the real candles around [trade] from [candles] (verified cache or a fresh provider
     * download). [now] only bounds the right edge of a still-open position.
     */
    fun window(
        trade: PaperTrade,
        candles: List<Candle>,
        padding: Int = PADDING_BARS,
        now: Long = System.currentTimeMillis(),
    ): Window {
        val interval = trade.interval
        val step = interval.millis
        val pad = padding.coerceIn(0, 60)
        val entryBar = barTimeOf(trade.openedAt, interval)
        val exitBar = trade.closedAt?.let { barTimeOf(it, interval) }
        val rightAnchor = exitBar ?: barTimeOf(maxOf(now, trade.openedAt), interval)
        val from = entryBar - pad * step
        val to = rightAnchor + pad * step

        val selected = candles.asSequence()
            .filter { it.time in from..to }
            .distinctBy { it.time }
            .sortedBy { it.time }
            .toList()
        // Never thin out or average bars to fit: cut the tail and say the exit is off screen.
        val trimmed = if (selected.size > MAX_BARS) selected.take(MAX_BARS) else selected
        val shownEntry = trimmed.firstOrNull { it.time == entryBar }?.time
        val shownExit = exitBar?.let { bar -> trimmed.firstOrNull { it.time == bar }?.time }
        val exitSatisfied = exitBar == null || shownExit != null
        val coverage = when {
            trimmed.size < 2 -> Coverage.MISSING
            shownEntry != null && exitSatisfied -> Coverage.FULL
            else -> Coverage.PARTIAL
        }
        return Window(
            bars = trimmed,
            coverage = coverage,
            entryBarTime = shownEntry,
            exitBarTime = shownExit,
            requestedFrom = from,
            requestedTo = to,
            detail = detail(trade, trimmed, coverage, shownEntry, exitBar, shownExit, selected.size),
        )
    }

    private fun detail(
        trade: PaperTrade,
        bars: List<Candle>,
        coverage: Coverage,
        shownEntry: Long?,
        exitBar: Long?,
        shownExit: Long?,
        selectedSize: Int,
    ): String = when (coverage) {
        Coverage.MISSING ->
            "کندل واقعی این بازه روی گوشی نیست (${trade.symbol} · ${trade.interval.label}). " +
                "نبود تاریخچه با کندل ساختگی پر نمی‌شود؛ اگر کلید دارید می‌توانید همین بازه را از ناشر بگیرید."
        Coverage.PARTIAL -> buildString {
            append("${bars.size} کندل واقعی از این بازه موجود است؛ پوشش ناقص: ")
            val gaps = mutableListOf<String>()
            if (shownEntry == null) gaps += "کندل لحظهٔ ورود در تاریخچهٔ محلی نیست"
            if (exitBar != null && shownExit == null) {
                gaps += if (selectedSize > bars.size) "بازه طولانی‌تر از سقف نمایش است و کندل خروج بیرون از تصویر ماند"
                else "کندل لحظهٔ خروج در تاریخچهٔ محلی نیست"
            }
            append(gaps.joinToString(" · "))
            append(". خطوط ورود/SL/TP از رکورد ژورنال‌اند، نه از این کندل‌ها.")
        }
        Coverage.FULL ->
            "${bars.size} کندل واقعی ${trade.interval.label} حول همین معامله؛ " +
                (if (trade.isOpen) "پوزیشن هنوز باز است و سمت راست تا آخرین کندل دریافتی است. "
                else "کندل ورود و خروج هر دو در تصویرند. ") +
                "قیمت ورود/خروج ثبت‌شده در ژورنال است؛ اجرای بروکر، اسپرد و لغزش واقعی در آن نیست."
    }
}
