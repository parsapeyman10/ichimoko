package com.aurum.edge.core

import java.util.Locale

/**
 * نام شاخص (خوانا) برای نمادها.
 *
 * چرا؟ در ژورنال و کارت‌ها، نمادها با کدهای تکی/دوتایی (`BTC`، `XAU`، `DOW`) نشان داده می‌شدند که
 * نه گویاست و نه یکتا. از ۳.۹.۳۵ هر نماد یک **نام شاخص فارسی** دارد و کد اصلی هم کنارش می‌آید
 * (`بیت‌کوین · BTC/USDT`)، تا کاربر بدون حفظ کردن کد، ابزار را بشناسد.
 */
object SymbolDisplay {

    /** نام شاخص فارسی هر نماد (کلید = نماد نرمال‌شدهٔ بزرگ). */
    private val NAMES_FA: Map<String, String> = mapOf(
        // رمزارزها
        "BTC/USDT" to "بیت‌کوین",
        "ETH/USDT" to "اتریوم",
        "SOL/USDT" to "سولانا",
        "BNB/USDT" to "بی‌ان‌بی",
        "XRP/USDT" to "ریپل",
        "DOGE/USDT" to "دوج‌کوین",
        "ADA/USDT" to "کاردانو",
        "AVAX/USDT" to "آوالانچ",
        "LINK/USDT" to "چین‌لینک",
        "TON/USDT" to "تون‌کوین",
        "DOT/USDT" to "پولکادات",
        "SHIB/USDT" to "شیبا اینو",
        "PEPE/USDT" to "پپه",
        "SUI/USDT" to "سویی",
        "NEAR/USDT" to "نیر پروتکل",
        // فلزات
        "XAU/USD" to "طلا",
        "XAG/USD" to "نقره",
        "XPT/USD" to "پلاتین",
        "XPD/USD" to "پالادیوم",
        // جفت‌ارزهای اصلی
        "EUR/USD" to "یورو / دلار",
        "GBP/USD" to "پوند / دلار",
        "USD/JPY" to "دلار / ین ژاپن",
        "USD/CHF" to "دلار / فرانک سوئیس",
        "AUD/USD" to "دلار استرالیا / دلار",
        "USD/CAD" to "دلار / دلار کانادا",
        "NZD/USD" to "دلار نیوزیلند / دلار",
        // شاخص‌ها
        "DOW" to "داو جونز",
        "US30" to "داو جونز",
        "DJI" to "داو جونز",
        "US500" to "شاخص S&P ۵۰۰",
        "SPX" to "شاخص S&P ۵۰۰",
        "SPX500" to "شاخص S&P ۵۰۰",
        "US100" to "شاخص نزدک ۱۰۰",
        "NAS100" to "شاخص نزدک ۱۰۰",
        "NDX" to "شاخص نزدک ۱۰۰",
        "GER40" to "شاخص داکس آلمان",
        "DAX" to "شاخص داکس آلمان",
        "UK100" to "شاخص فوتسی ۱۰۰",
        "JP225" to "شاخص نیکی ۲۲۵",
    )

    private val CRYPTO_NAMES_FA: Map<String, String> = mapOf(
        "BTC" to "بیت‌کوین",
        "ETH" to "اتریوم",
        "SOL" to "سولانا",
        "BNB" to "بی‌ان‌بی",
        "XRP" to "ریپل",
        "DOGE" to "دوج‌کوین",
        "ADA" to "کاردانو",
        "AVAX" to "آوالانچ",
        "LINK" to "چین‌لینک",
        "TON" to "تون‌کوین",
        "DOT" to "پولکادات",
        "SHIB" to "شیبا اینو",
        "PEPE" to "پپه",
        "SUI" to "سویی",
        "NEAR" to "نیر پروتکل",
    )

    /** نماد نرمال‌شده (بزرگ، بدون فاصله). */
    private fun key(symbol: String): String = symbol.trim().uppercase(Locale.ROOT).replace(" ", "")

    /**
     * نام شاخص فارسی نماد.
     *
     * اگر نماد ناشناخته باشد، بهترین توصیف ممکن ساخته می‌شود: برای جفت‌های رمزارزی
     * (`XYZ/USDT`) نام دارایی پایه (اگر در فهرست باشد) و در غیر این صورت خودِ نماد نرمال‌شده.
     */
    fun nameFa(symbol: String): String {
        val k = key(symbol)
        if (k.isBlank()) return "—"
        NAMES_FA[k]?.let { return it }
        if (k.contains("/")) {
            val base = k.substringBefore("/")
            val quote = k.substringAfter("/")
            CRYPTO_NAMES_FA[base]?.let { name ->
                return if (quote == "USDT") name else "$name / $quote"
            }
            return k
        }
        CRYPTO_NAMES_FA[k]?.let { return it }
        return k
    }

    /** برچسب کامل: نام شاخص + کد اصلی (برای یکتایی و ارجاع دقیق). */
    fun labelFa(symbol: String): String {
        val k = key(symbol)
        val name = nameFa(symbol)
        return if (name == k) k else "$name · $k"
    }

}
