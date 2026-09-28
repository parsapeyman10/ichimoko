package com.aurum.edge.core

/** Receipt freshness is independent of socket existence and of the Android screen state.
 * This is a presentation/safety downgrade ONLY: it never fabricates a tick or extends its time.
 */
object FeedLiveness {
    const val MAX_RECEIPT_AGE_MS = 90_000L
    private const val FUTURE_CLOCK_TOLERANCE_MS = 10_000L

    fun hasRecentReceipt(status: FeedStatus, now: Long = System.currentTimeMillis()): Boolean =
        status.lastSuccessAt?.let { receiptAt ->
            now - receiptAt in -FUTURE_CLOCK_TOLERANCE_MS..MAX_RECEIPT_AGE_MS
        } == true

    fun display(status: FeedStatus, now: Long = System.currentTimeMillis()): FeedStatus = when {
        status.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) && !hasRecentReceipt(status, now) -> {
            status.copy(mode = FeedMode.DELAYED,
                detail = "بیش از ۹۰ ثانیه تیک/کندل تازه دریافت نشده؛ بازار ممکن است بسته باشد یا شبکه/سهمیه محدود شده باشد. اتصال دوباره بررسی می‌شود؛ این قیمت آنلاین نیست")
        }
        status.mode == FeedMode.DELAYED && hasRecentReceipt(status, now) -> {
            // A fresh local receipt must win over a stale reconnect/error label. This protects the
            // top header/banner when a 1-second fallback tick arrives while Twelve/REST is noisy.
            val restoredMode = if (looksLikeLiveTickProvider(status.provider)) FeedMode.LIVE else FeedMode.POLLING
            status.copy(mode = restoredMode, detail = freshDetail(status.detail))
        }
        else -> status
    }

    private fun freshDetail(detail: String): String =
        if (detail.contains("۹۰") || detail.contains("90") || detail.contains("قدیمی") ||
            detail.contains("دیررس") || detail.contains("آنلاین نیست")) {
            "دریافت تازهٔ واقعی معتبر است؛ برچسب دیررس قبلی اصلاح شد"
        } else detail

    private fun looksLikeLiveTickProvider(provider: String): Boolean {
        val p = provider.lowercase()
        return "websocket" in p || "swissquote" in p || "gold-api" in p ||
            "فید زنده" in provider || "فید رایگان" in provider || "جایگزین" in provider
    }
}
