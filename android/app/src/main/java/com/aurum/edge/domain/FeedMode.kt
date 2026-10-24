package com.aurum.edge.domain

/**
 * Connection truth for the UI. OFFLINE is a first-class state — never replaced by invented data.
 * Lives in [domain] so both [core] and [data] can depend on it without a cycle.
 */
enum class FeedMode(val label: String) {
    NO_KEY("کلید API وارد نشده"),
    CONNECTING("در حال اتصال"),
    LIVE("زنده — تیک تازه"),
    POLLING("کندل/تاریخچه آنلاین دوره‌ای — نه تیک زنده"),
    MARKET_CLOSED("تعطیلی معمول بازار؛ دریافت قیمت متوقف"),
    DELAYED("دادهٔ بازار قدیمی؛ اتصال/بازار را بررسی کنید"),
    OFFLINE("آفلاین"),
}
