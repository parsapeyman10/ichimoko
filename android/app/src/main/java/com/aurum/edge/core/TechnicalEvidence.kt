package com.aurum.edge.core

/** Current four-layer V1 evidence. Seven/eight-condition entries are historic records only;
 * do not use them to authorize new automatic entries. News is a separate AI supervision layer.
 */
object TechnicalEvidence {
    const val CURRENT_COUNT = 4
    const val LEGACY_COUNT = 8
    private val REQUIRED_NAMES = setOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
        "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی")
    private val CRYPTO_NAMES = setOf("فشردگی · ۲۰ کندل", "انفجار · حجم/ADX/چیکو",
        "فیلتر کلان · ۵۲/۱۰۳ و M15/H1", "ورود · کندل/ریسک/سشن")
    private val ALS5_NAMES = setOf(
        "رژیم و ساختار بازار · ALS-5",
        "کیفیت ستاپ و سطح نقدینگی · ALS-5",
        "تأیید ورود و شکست ریزساختار · ALS-5",
        "نسبت ریسک به ریوارد و شرایط اجرا · ALS-5",
    )
    /** BTC-ARES has its own four evidence rows; they are not interchangeable with ALS-5's. */
    private val BTC_ARES_NAMES = setOf(
        "رژیم و نوسان · BTC-ARES",
        "ساختار و سطح · BTC-ARES",
        "تأیید و موتور · BTC-ARES",
        "ریسک، هزینه و اجرا · BTC-ARES",
    )
    /** ALS-5S rows: silver's evidence is not gold's and not V1's. */
    private val ALSS_NAMES = setOf(
        "رژیم و نوسان · ALS-5S",
        "ساختار و سطح · ALS-5S",
        "تأیید و موتور · ALS-5S",
        "هزینه و اجرا · ALS-5S",
    )
    /** EUR-ARES rows: the euro-dollar evidence is pip-based and belongs to nobody else. */
    private val EUR_ARES_NAMES = setOf(
        "رژیم و نوسان · EUR-ARES",
        "ساختار و سطح · EUR-ARES",
        "تأیید و موتور · EUR-ARES",
        "هزینه، پیپ و اجرا · EUR-ARES",
    )
    private const val NEWS_LABEL = "خبر/تقویم · داده‌کاوی ژورنال"

    fun items(signal: Signal): List<ConfluenceItem> = signal.confluence.filterNot { it.name == NEWS_LABEL }

    fun confirmed(items: List<ConfluenceItem>): Boolean =
        items.size == CURRENT_COUNT &&
            (items.map { it.name }.toSet() == REQUIRED_NAMES || items.map { it.name }.toSet() == ALS5_NAMES) &&
            items.all { item -> when (item.scorePercent) {
                25 -> item.ok && item.status == ConfluenceStatus.CONFIRMED
                in 1..24 -> !item.ok && item.status == ConfluenceStatus.PARTIAL
                0 -> !item.ok && item.status == ConfluenceStatus.CONFLICT
                else -> false
            } } &&
            items.sumOf { it.scorePercent ?: 0 } in 60..100

    fun confirmed(signal: Signal): Boolean {
        val core = items(signal)
        if (signal.btcAres != null) {
            // BTC-ARES: the per-engine record is the authority. A crypto compression record must
            // not be able to authorise a BTC entry, and vice versa.
            return signal.btcAres.matches(signal) &&
                core.size == CURRENT_COUNT && core.map { it.name }.toSet() == BTC_ARES_NAMES &&
                core.all { it.ok && it.status == ConfluenceStatus.CONFIRMED && it.scorePercent == 25 } &&
                kotlin.math.abs(core.sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6
        }
        if (signal.eurAres != null) {
            // EUR-ARES: the per-pair record is the authority. A gold, silver or V1 record can never
            // authorise a euro-dollar entry, and this branch cannot authorise any other symbol.
            return signal.eurAres.matches(signal) &&
                core.size == CURRENT_COUNT && core.map { it.name }.toSet() == EUR_ARES_NAMES &&
                core.all { it.ok && it.status == ConfluenceStatus.CONFIRMED && it.scorePercent == 25 } &&
                kotlin.math.abs(core.sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6
        }
        if (signal.als5s != null) {
            // ALS-5S: the per-engine record is the authority, so a gold or V1 record can never
            // authorise a silver entry (and vice versa).
            return signal.als5s.matches(signal) &&
                core.size == CURRENT_COUNT && core.map { it.name }.toSet() == ALSS_NAMES &&
                core.all { it.ok && it.status == ConfluenceStatus.CONFIRMED && it.scorePercent == 25 } &&
                kotlin.math.abs(core.sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6
        }
        if (signal.cryptoBreakout != null) return signal.cryptoBreakout.matches(signal) &&
            core.size == CURRENT_COUNT && core.map { it.name }.toSet() == CRYPTO_NAMES &&
            core.all { it.ok && it.status == ConfluenceStatus.CONFIRMED && it.scorePercent == 25 } &&
            signal.confidence == 100.0
        if (signal.als5Opportunity != null && signal.isActionable) {
            return signal.als5Opportunity.matches(signal) &&
                confirmed(core) && signal.confidence.isFinite() &&
                kotlin.math.abs(core.sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6
        }
        return confirmed(core) && signal.confidence.isFinite() &&
            kotlin.math.abs(core.sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6
    }

    fun confirmedRecords(items: List<PaperConditionRecord>): Boolean = when (items.size) {
        CURRENT_COUNT -> (items.map { it.name }.toSet() == REQUIRED_NAMES ||
                items.map { it.name }.toSet() == REQUIRED_NAMES - "پشتهٔ ۳ تایم‌فریمی" + "پشتهٔ ۷ تایم‌فریمی" ||
                items.map { it.name }.toSet() == CRYPTO_NAMES ||
                items.map { it.name }.toSet() == ALS5_NAMES ||
                items.map { it.name }.toSet() == BTC_ARES_NAMES ||
                items.map { it.name }.toSet() == ALSS_NAMES) &&
            items.all { it.status in setOf("CONFIRMED", "PARTIAL", "CONFLICT") }
        7, LEGACY_COUNT -> items.map { it.name }.distinct().size == items.size &&
            items.all { it.status == "CONFIRMED" }
        else -> false
    }
}
