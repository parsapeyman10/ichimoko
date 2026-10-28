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
    /** نام فارسی مرجع دریافت دیتا و قوانین معاملاتی آن */
    val dataProviderFa: String = venue,
    /** کارمزد Maker در هر سمت (bps) */
    val makerFeeBps: Double = commissionBpsPerSide,
    /** کارمزد Taker در هر سمت (bps) */
    val takerFeeBps: Double = commissionBpsPerSide,
    /** حداقل حجم مجاز سفارش در مرجع دیتا */
    val minOrderQuantity: Double = 0.0001,
    /** گام تغییر حجم سفارش (Lot Step / Step Size) */
    val quantityStep: Double = 0.0001,
    /** حداقل ارزش دلاری سفارش (Min Notional USD) */
    val minNotionalUsd: Double = 5.0,
    /** اندازه ۱ لات استاندارد */
    val contractSizeUnits: Double = 1.0,
) {
    /** هزینهٔ اسپرد برای یک حجم مشخص، بر حسب دلار. */
    fun spreadCostUsd(price: Double, quantity: Double): Double =
        if (spreadBps > 0.0) price * quantity * spreadBps / 10_000.0 else spreadPrice * quantity

    /** کمیسیون یک سمت برای یک حجم مشخص، بر حسب دلار. */
    fun commissionUsd(price: Double, quantity: Double): Double =
        if (commissionBpsPerSide > 0.0) price * quantity * commissionBpsPerSide / 10_000.0
        else commissionPerUnit * quantity

    /** مجموع کل کارمزد و کمیسیون رفت‌وبرگشت (ورود + خروج + اسپرد) بر حسب دلار. */
    fun totalRoundTripFeeUsd(price: Double, quantity: Double): Double =
        commissionUsd(price, quantity) * 2.0 + spreadCostUsd(price, quantity)

    /** فاصله قیمتی معادل کل کارمزد رفت‌وبرگشت و اسپرد روی هر واحد از دارایی (برای جبران کارمزد در حد سود). */
    fun feePriceOffsetPerUnit(price: Double): Double {
        val commPerSide = if (commissionBpsPerSide > 0.0) price * commissionBpsPerSide / 10_000.0 else commissionPerUnit
        val spr = if (spreadBps > 0.0) price * spreadBps / 10_000.0 else spreadPrice
        return commPerSide * 2.0 + spr
    }

    /** توضیح کامل فارسی قوانین خرید، کمیسیون، کارمزد و اسپرد مرجع دیتا برای یک معامله. */
    fun buildRulesSummaryFa(price: Double, quantity: Double): String {
        val entryComm = commissionUsd(price, quantity)
        val exitComm = entryComm
        val spreadUsd = spreadCostUsd(price, quantity)
        val totalFee = entryComm + exitComm + spreadUsd
        val rateDesc = if (commissionBpsPerSide > 0.0) {
            String.format(
                java.util.Locale.US,
                "کارمزد صرافی: %.2f%% در هر سمت (رفت‌وبرگشت %.2f%%) + اسپرد %.1f bps",
                commissionBpsPerSide / 100.0,
                (commissionBpsPerSide * 2.0) / 100.0,
                spreadBps,
            )
        } else if (commissionPerUnit > 0.0) {
            val perLotSide = commissionPerUnit * contractSizeUnits
            String.format(
                java.util.Locale.US,
                "کمیسیون ECN: $%.2f/لات در هر سمت ($%.2f رفت‌وبرگشت) + اسپرد %s",
                perLotSide,
                perLotSide * 2.0,
                tickFormatted(spreadPrice),
            )
        } else {
            String.format(java.util.Locale.US, "کمیسیون: $0.00 (فقط اسپرد بازار: %s)", tickFormatted(spreadPrice))
        }
        return String.format(
            java.util.Locale.US,
            "مرجع دیتا: %s · %s · هزینه این معامله: کمیسیون رفت‌وبرگشت $%.3f + اسپرد $%.3f = مجموع کل کارمزد $%.3f (تیک: %s · حداقل سفارش: $%.0f)",
            dataProviderFa,
            rateDesc,
            entryComm + exitComm,
            spreadUsd,
            totalFee,
            tickFormatted(tickSize),
            minNotionalUsd,
        )
    }

    private fun tickFormatted(v: Double): String = when {
        v >= 0.01 -> String.format(java.util.Locale.US, "%.2f", v)
        v >= 0.001 -> String.format(java.util.Locale.US, "%.3f", v)
        else -> String.format(java.util.Locale.US, "%.5f", v)
    }

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
    val CRYPTO_SPOT = "کاغذی USDT (قیمت نوبیتکس؛ مدل هزینهٔ مرجع Binance VIP0، نه کارمزد نوبیتکس)"
    val US_STOCK = "NYSE/Nasdaq (IBKR Lite, $0) · ESMA 5:1"

    /** Spec مرجعِ همان نماد، با اعداد واقعی بازار. */
    fun of(symbol: String): VenueSpec = when (AssetClass.of(symbol)) {
        AssetClass.CRYPTO -> {
            val clean = symbol.trim().uppercase()
            val (minQty, stepQty, tick) = when {
                clean.startsWith("BTC") -> Triple(0.0001, 0.00001, 0.01)
                clean.startsWith("ETH") || clean.startsWith("BNB") -> Triple(0.001, 0.0001, 0.01)
                clean.startsWith("SOL") -> Triple(0.01, 0.001, 0.01)
                else -> Triple(0.1, 0.01, 0.0001)
            }
            VenueSpec(
                venue = CRYPTO_SPOT,
                leverageCap = 2,
                tickSize = tick,
                commissionBpsPerSide = 10.0,
                spreadBps = 1.0,
                source = "Nobitex USDT public trade prices; paper cost benchmark: Binance Spot VIP0 0.10%/side + 1bp spread, NOT a verified Nobitex fee ($ESMA_CAPS)",
                dataProviderFa = "Binance Spot VIP0 / Kraken (کارمزد ۰.۱۰٪ در هر سمت + اسپرد ۱ bps)",
                makerFeeBps = 10.0,
                takerFeeBps = 10.0,
                minOrderQuantity = minQty,
                quantityStep = stepQty,
                minNotionalUsd = 5.0,
                contractSizeUnits = 1.0,
            )
        }

        AssetClass.STOCK -> VenueSpec(
            venue = US_STOCK,
            leverageCap = 5,
            tickSize = 0.01,
            commissionBpsPerSide = 0.0,
            spreadPrice = 0.01,
            source = "US equities: $0 commission (IBKR Lite), 1-cent tick, SEC fee $27.80 per $1M on sells ($ESMA_CAPS)",
            dataProviderFa = "NYSE/Nasdaq · Yahoo/IBKR (کمیسیون $0.00 + اسپرد ۰.۰۱ دلار)",
            minOrderQuantity = 0.01,
            quantityStep = 0.01,
            minNotionalUsd = 1.0,
            contractSizeUnits = 1.0,
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
                dataProviderFa = "Swissquote / IC Markets Raw ECN (کمیسیون $3.50/لات در هر سمت + اسپرد خام ECN)",
                minOrderQuantity = 1_000.0, // 0.01 lot
                quantityStep = 100.0,
                minNotionalUsd = 10.0,
                contractSizeUnits = 100_000.0,
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
                dataProviderFa = "Swissquote / LMAX / IC Markets XAUUSD (۱ لات = ۱۰۰ اونس · کمیسیون $3.50/لات در هر سمت + اسپرد $0.12/oz)",
                minOrderQuantity = 0.01,
                quantityStep = 0.01,
                minNotionalUsd = 10.0,
                contractSizeUnits = 100.0,
            )

            symbol.uppercase().startsWith("XAG") || symbol.uppercase() == "SILVER" -> VenueSpec(
                venue = "COMEX/CFD silver · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.001,
                commissionPerUnit = 0.0007,
                spreadPrice = 0.02,
                source = "COMEX SI contract 5,000 oz, tick $0.001; CFD spread ≈ $0.02 · $ESMA_CAPS",
                dataProviderFa = "Swissquote / COMEX Silver (۱ لات = ۵,۰۰۰ اونس · کمیسیون $3.50/لات در هر سمت + اسپرد $0.02/oz)",
                minOrderQuantity = 0.1,
                quantityStep = 0.01,
                minNotionalUsd = 10.0,
                contractSizeUnits = 5_000.0,
            )

            symbol.uppercase().startsWith("NATGAS") -> VenueSpec(
                venue = "NYMEX Henry Hub (NG) · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.001,
                spreadPrice = 0.002,
                source = "NYMEX NG contract 10,000 MMBtu, tick $0.001 · $ESMA_CAPS",
                dataProviderFa = "NYMEX Henry Hub (۱ لات = ۱۰,۰۰۰ MMBtu · اسپرد $0.002)",
                minOrderQuantity = 1.0,
                quantityStep = 0.1,
                minNotionalUsd = 10.0,
                contractSizeUnits = 10_000.0,
            )

            symbol.uppercase().startsWith("COPPER") -> VenueSpec(
                venue = "COMEX Copper (HG) · ESMA 10:1",
                leverageCap = 10,
                tickSize = 0.0005,
                spreadPrice = 0.001,
                source = "COMEX HG contract 25,000 lbs, tick $0.0005 · $ESMA_CAPS",
                dataProviderFa = "COMEX Copper (۱ لات = ۲۵,۰۰۰ پوند · اسپرد $0.001)",
                minOrderQuantity = 1.0,
                quantityStep = 0.1,
                minNotionalUsd = 10.0,
                contractSizeUnits = 25_000.0,
            )

            else -> VenueSpec(
                venue = COMMODITY,
                leverageCap = 10,
                tickSize = 0.01,
                spreadPrice = 0.03,
                source = "NYMEX WTI (CL) 1,000 bbl, tick $0.01, typical spread 1–4 cents; ICE Brent (BZ) same tick · $ESMA_CAPS",
                dataProviderFa = "NYMEX WTI / ICE Brent (۱ لات = ۱,۰۰۰ بشکه · اسپرد ۳ سنت)",
                minOrderQuantity = 0.1,
                quantityStep = 0.01,
                minNotionalUsd = 10.0,
                contractSizeUnits = 1_000.0,
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
