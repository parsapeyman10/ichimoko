package com.aurum.edge.data

/** A connection probe is NOT an entry judgement. These codes describe only the observed
 * execution of PaperAutoTrader's model-review branch, never presumed AI performance.
 */
enum class AiEntryEffect(val action: String, val label: String) {
    VETO("ai_veto", "وتوی واقعی مدل؛ ورود این سیگنال متوقف شد"),
    NON_VETO("ai_non_veto", "مدل صریحاً مخالفت نکرد؛ تصمیم/سطوح فنی و ریسک همچنان حاکم‌اند"),
    NO_VERDICT("ai_no_verdict", "پاسخ مدل رسید اما دربارهٔ ارزندگی ورود حکم صریح نداشت"),
    INVALID("ai_invalid", "تست اتصال موفق بود ولی پاسخ معتبرِ طرح ورود دریافت نشد"),
    UNAVAILABLE("ai_unavailable", "مدل هنگام بررسی این ورود در دسترس نبود؛ مسیر فنی ادامه یافت");

    companion object {
        fun fromAction(action: String): AiEntryEffect? = entries.firstOrNull { it.action == action }

        fun fromResponse(connected: Boolean, plan: AiTradePlan?): AiEntryEffect = when {
            !connected -> UNAVAILABLE
            plan == null -> INVALID
            !plan.worthAnswered -> NO_VERDICT
            !plan.worth -> VETO
            else -> NON_VETO
        }
    }
}
