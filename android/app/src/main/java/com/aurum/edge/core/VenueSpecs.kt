package com.aurum.edge.core

import kotlin.math.max

/**
 * واقعی‌ترین مرجع قابل‌اتکا برای مقادیر SL/TP/Risk: به‌جای اعداد دلبخواهی، همان اعدادی که
 * «کارگزاران/صرافی‌های معتبر» و «قانون» روی همان دارایی اعمال می‌کنند.
 *
 * منابع (همه عمومی و قابل راستی‌آزمایی):
 *  - سقف اهرم خرده‌فروشی ESMA (اجباری از ۱ اوت ۲۰۱۸ و دائمی‌شده توسط ناظرهای ملی از جمله BaFin):
 *      جفت‌ارزهای اصلی (USD, EUR, JPY, GBP, CAD, CHF) → ۳۰:۱
 *      جفت‌ارزهای غیراصلی + طلا + شاخص‌های اصلی → ۲۰:۱
 *      سایر کالاها (نقره، نفت، گاز، مس) و شاخص‌های کوچک → ۱۰:۱
 *      سهام تکی → ۵:۱   |   رمزارز → ۲:۱
 *  - IC Markets Raw Spread (نهاد اروپایی، ۱:۳۰): میانگین اسپرد EUR/USD ≈ ۰٫۱ پیپ و
 *    کمیسیون ۳٫۵۰ دلار برای هر لات (۱۰۰٬۰۰۰ واحد) در هر سمت؛ طلا (XAU/USD) ≈ ۰٫۰۵–۰٫۱۲ دلار
 *    برای هر اونس + ۳٫۵۰ دلار برای هر لات (۱۰۰ اونس) در هر سمت.
 *  - Binance Spot (VIP0): کارمزد ۰٫۱۰٪ در هر سمت (۰٫۰۷۵٪ با BNB)؛ tick و حداقل‌ها از
 *    فیلترهای واقعی همان صرافی خوانده می‌شود (instruments).
 *  - سهام آمریکا (NYSE/Nasdaq): کمیسیون صفر (IBKR Lite) و tick یک سنت؛ کارمزد SEC روی فروش
 *    عملاً ناچیز (۲۷٫۸۰ دلار در هر ۱ میلیون دلار معامله).
 *  - NYMEX WTI / ICE Brent: tick یک سنت و اسپرد معمول ۱–۴ سنت؛ CONTRACT = ۱۰۰۰ بشکه.
 *
 * این اعداد «ورودی هزینهٔ اجرا» هستند، نه وعدهٔ سود. هیچ‌جا سواپ/بهره شبانه تخمین زده نمی‌شود،
 * چون این نرخ‌ها هر روز تغییر می‌کنند و اگر واقعی در دسترس نباشد، صفر گزارش می‌شود.
 */
data class VenueSpec(
    /** نام و نوع حسابِ مرجعی که اعداد از آن آمده است. */
    val venue: String,
    /** سقف اهرم قانونی/عملی همان بازار (ESMA برای CFD). */
    val leverageCap: Int,
    /** کوچک‌ترین واحد حرکت قیمت (tick) روی همان نماد. */
    val tickSize: Double,
    /** کمیسیون هر سمت بر حسب ارز کوت (واحد معامله). ۰ = فقط اسپرد. */
    val commissionPerUnit: Double = 0.0,
    /** کمیسیون هر سمت بر حسب bps از ارزش معامله (صرافی‌ها). ۰ = فقط اسپرد. */
    val commissionBpsPerSide: Double = 0.0,
    /** کل اسپرد خرید/فروش بر حسب واحد قیمت (هزینهٔ گذشتن از اسپرد، رفت‌وبرگشت). */
    val spreadPrice: Double = 0.0,
    /** کل اسپرد بر حسب bps از قیمت (بازارهایی که اسپرد را درصدی اعلام می‌کنند). */
    val spreadBps: Double = 0.0,
    /** توضیح منبع، همان‌طور که در UI هم نشان داده می‌شود. */
    val source: String,
) {
    /** هزینهٔ اسپرد برای یک حجم مشخص، بر حسب دلار. */
    fun spreadCostUsd(price: Double, quantity: Double): Double =
        if (spreadBps > 0.0) price * quantity * spreadBps / 10_000.0 else spreadPrice * quantity

    /** کمیسیون یک سمت برای یک حجم مشخص، بر حسب دلار. */
    fun commissionUsd(price: Double, quantity: Double): Double =
        if (commissionBpsPerSide > 0.0) price * quantity * commissionBpsPerSide / 10_000.0
        else commissionPerUnit * quantity

    /**
     * کوچک‌ترین فاصلهٔ مجاز حد ضرر بر اساس قواعد واقعی بازار: حد ضرر باید بیرون از اسپرد و
     * حداقل دو tick فاصله داشته باشد تا نویز عادی و اسپرد آن را نزند.
     */
    fun minStopDistance(price: Double): Double =
        max(2.0 * (if (spreadBps > 0.0) price * spreadBps / 10_000.0 else spreadPrice), 2.0 * tickSize)
}

object VenueSpecs {
    /** ارزهای جفت‌ارز «اصلی» طبق تعریف ESMA. */
    private val MAJOR_CURRENCIES = setOf("USD", "EUR", "JPY", "GBP", "CAD", "CHF")

    private const val IC_MARKETS =
        "IC Markets Raw Spread (EU, 1:30) — EUR/USD ≈ 0.1 pip + $3.50/lot/side; XAU/USD ≈ $0.05–0.12/oz + $3.50/lot/side"
    private const val ESMA_CAPS = "ESMA retail leverage caps (permanent since 1 Aug 2018)"

    val FX_MAJOR = "IC Markets Raw Spread · ESMA 30:1"
    val GOLD = "LMAX/IC Markets XAUUSD · ESMA 20:1"
    val COMMODITY = "NYMEX/ICE contract ticks · ESMA 10:1"
    val CRYPTO_SPOT = "Binance Spot (VIP0, 0.10%/side) · ESMA 2:1"
    val US_STOCK = "NYSE/Nasdaq (IBKR Lite, $0) · ESMA 5:1"

    /** Spec مرجعِ همان نماد، با اعداد واقعی بازار. */
    fun of(symbol: String): VenueSpec = when (AssetClass.of(symbol)) {
        AssetClass.CRYPTO -> VenueSpec(
            venue = CRYPTO_SPOT,
            leverageCap = 2,
            tickSize = 0.01,
            commissionBpsPerSide = 10.0,
            spreadBps = 1.0,
            source = "Binance Spot VIP0 taker fee 0.10% per side ($ESMA_CAPS)",
        )

        AssetClass.STOCK -> VenueSpec(
            venue = US_STOCK,
            leverageCap = 5,
            tickSize = 0.01,
            commissionBpsPerSide = 0.0,
            spreadPrice = 0.01,
            source = "US equities: $0 commission (IBKR Lite), 1-cent tick, SEC fee $27.80 per $1M on sells ($ESMA_CAPS)",
        )

        AssetClass.FOREX -> {
            val clean = symbol.trim().uppercase()
            val jpyQuote = clean.endsWith("JPY")
            val major = isMajorPair(clean)
            VenueSpec(
                venue = if (major) FX_MAJOR else "IC Markets Raw Spread · ESMA 20:1 (non-major)",
                leverageCap = if (major) 30 else 20,
                tickSize = if (jpyQuote) 0.001 else 0.00001,
                // ۳٫۵۰ دلار برای هر لات = ۱۰۰٬۰۰۰ واحد → ۰٫۰۰۰۰۳۵ دلار برای هر واحد، در هر سمت.
                commissionPerUnit = 0.000035,
                // ۰٫۱ پیپ روی جفت‌های معمولی و ۰٫۲ پیپ روی کراس‌ها (میانگین روزهای فعال).
                spreadPrice = when {
                    jpyQuote && major -> 0.001
                    jpyQuote -> 0.002
                    major -> 0.00001
                    else -> 0.00002
                },
                source = "$IC_MARKETS · $ESMA_CAPS",
            )
        }

        AssetClass.COMMODITY -> when {
            symbol.uppercase().startsWith("XAU") || symbol.uppercase() == "GOLD" -> VenueSpec(
                venue = GOLD,
                leverageCap = 20,
                tickSize = 0.01,
                commissionPerUnit = 0.035,
                spreadPrice = 0.12,
                source = "$IC_MARKETS · gold 1 lot = 100 oz · $ESMA_CAPS",
            )

            symbol.uppercase().startsWith("XAG") || symbol.uppercase() == "SILVER" -> VenueSpec(
                venue = "COMEX/CFD silver · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.001,
                commissionPerUnit = 0.0007,
                spreadPrice = 0.02,
                source = "COMEX SI contract 5,000 oz, tick $0.001; CFD spread ≈ $0.02 · $ESMA_CAPS",
            )

            symbol.uppercase().startsWith("NATGAS") -> VenueSpec(
                venue = "NYMEX Henry Hub (NG) · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.001,
                spreadPrice = 0.002,
                source = "NYMEX NG contract 10,000 MMBtu, tick $0.001 · $ESMA_CAPS",
            )

            symbol.uppercase().startsWith("COPPER") -> VenueSpec(
                venue = "COMEX Copper (HG) · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.0005,
                spreadPrice = 0.001,
                source = "COMEX HG contract 25,000 lbs, tick $0.0005 · $ESMA_CAPS",
            )

            else -> VenueSpec(
                venue = COMMODITY,
                leverageCap = 10,
                tickSize = 0.01,
                spreadPrice = 0.03,
                source = "NYMEX WTI (CL) 1,000 bbl, tick $0.01, typical spread 1–4 cents; ICE Brent (BZ) same tick · $ESMA_CAPS",
            )
        }
    }

    /** آیا جفت‌ارز در تعریف ESMA «اصلی» است (هر دو ارز از شش ارز اصلی)؟ */
    fun isMajorPair(symbol: String): Boolean {
        val clean = symbol.trim().uppercase()
        val parts = clean.split('/')
        if (parts.size != 2) return false
        return parts.all { it in MAJOR_CURRENCIES }
    }

    /** حداقل فاصلهٔ مجاز حد ضرر بر اساس spec همان نماد. */
    fun minStopDistance(symbol: String, price: Double): Double = of(symbol).minStopDistance(price)
}
