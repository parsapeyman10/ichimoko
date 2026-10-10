package com.aurum.edge.core

/** Current four-layer V1 evidence. Seven/eight-condition entries are historic records only;
 * do not use them to authorize new automatic entries. News is a separate AI supervision layer.
 */
object TechnicalEvidence {
    const val CURRENT_COUNT = 4
    const val LEGACY_COUNT = 8
    private val REQUIRED_NAMES = setOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
        "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۷ تایم‌فریمی")
    private const val NEWS_LABEL = "خبر/تقویم · داده‌کاوی ژورنال"

    fun items(signal: Signal): List<ConfluenceItem> = signal.confluence.filterNot { it.name == NEWS_LABEL }

    fun confirmed(items: List<ConfluenceItem>): Boolean =
        items.size == CURRENT_COUNT && items.map { it.name }.toSet() == REQUIRED_NAMES &&
            items.all { item -> when (item.scorePercent) {
                25 -> item.ok && item.status == ConfluenceStatus.CONFIRMED
                in 1..24 -> !item.ok && item.status == ConfluenceStatus.PARTIAL
                0 -> !item.ok && item.status == ConfluenceStatus.CONFLICT
                else -> false
            } } &&
            items.sumOf { it.scorePercent ?: 0 } in 60..100

    fun confirmed(signal: Signal): Boolean = confirmed(items(signal)) &&
        signal.confidence.isFinite() &&
        kotlin.math.abs(items(signal).sumOf { it.scorePercent ?: 0 }.toDouble() - signal.confidence) < 1e-6

    fun confirmedRecords(items: List<PaperConditionRecord>): Boolean = when (items.size) {
        CURRENT_COUNT -> items.map { it.name }.toSet() == REQUIRED_NAMES &&
            items.all { it.status in setOf("CONFIRMED", "PARTIAL", "CONFLICT") }
        7, LEGACY_COUNT -> items.map { it.name }.distinct().size == items.size &&
            items.all { it.status == "CONFIRMED" }
        else -> false
    }
}
