package com.aurum.edge.core

/** Receipt freshness is independent of socket existence and of the Android screen state.
 * This is a presentation/safety downgrade ONLY: it never fabricates a tick or extends its time.
 */
object FeedLiveness {
    const val MAX_RECEIPT_AGE_MS = 90_000L

    fun hasRecentReceipt(status: FeedStatus, now: Long = System.currentTimeMillis()): Boolean =
        status.lastSuccessAt?.let { now - it in 0L..MAX_RECEIPT_AGE_MS } == true

    fun display(status: FeedStatus, now: Long = System.currentTimeMillis()): FeedStatus =
        if (status.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) && !hasRecentReceipt(status, now)) {
            status.copy(mode = FeedMode.DELAYED,
                detail = "بیش از ۹۰ ثانیه تیک/کندل تازه دریافت نشده؛ بازار ممکن است بسته باشد یا شبکه/سهمیه محدود شده باشد. اتصال دوباره بررسی می‌شود؛ این قیمت آنلاین نیست")
        } else status
}
