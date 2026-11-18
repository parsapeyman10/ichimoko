package com.aurum.edge.core

/**
 * کادر قیمتی چارت: از کدام کمینه و بیشینه محور عمودی ساخته شود.
 *
 * چرا این فایل جدا شد؟ روی دستگاه کاربر چارت یک بار **کاملاً خالی** شد (نسخهٔ ۳٫۱۲٫۰، گزارش
 * اسکرین‌شات): اعداد سرصفحه درست بودند ولی بوم نقاشی چیزی نکشید، چون در محاسبهٔ کادر یکی از
 * مقادیر عددی خراب (بی‌نهایت یا عددی چند مرتبه بزرگ‌تر از قیمت واقعی) وارد شده بود و همهٔ قیمت‌ها
 * به یک لبه فشرده شدند. این‌جا همان محاسبه با سه محافظ انجام می‌شود:
 *
 * ۱) هر مقدار غیرمتناهی (NaN / ±Infinity) نادیده گرفته می‌شود.
 * ۲) کادر پایه **فقط از کندل‌ها** ساخته می‌شود؛ کندل‌ها حقیقت قیمت‌اند و همیشه در دسترس‌اند.
 * ۳) خطوط و ابر فقط وقتی کادر را باز می‌کنند که در باند مجاز اطراف کندل‌ها باشند
 *    (`overlayBands` برابر نیم‌پهنای کندل‌ها). یک مقدار پرت نمی‌تواند کل چارت را ببلعد.
 */
object ChartScale {

    /** کمینه/بیشینهٔ محور قیمت. `span` همیشه مثبت و متناهی است. */
    data class Window(val min: Double, val max: Double) {
        val span: Double get() = (max - min).coerceAtLeast(1e-9)
    }

    /** پهنای پیش‌فرض باند خطوط بر حسب نیم‌پهنای کندل‌ها (یعنی ۳ برابر کل ارتفاع کندل‌ها از هر طرف). */
    const val DEFAULT_OVERLAY_BANDS: Double = 3.0

    /**
     * @param lows کف کندل‌های نمایان
     * @param highs سقف کندل‌های نمایان
     * @param overlays تنکان/کیجون/سنکو/ابر آینده
     * @param extras حد سود/حد ضرر معاملهٔ باز (باند پهن‌تر، چون می‌توانند دور باشند)
     * @param fallback وقتی هیچ کندل سالمی نیست (مثلاً لحظهٔ بارگذاری) — کادر حول این عدد ساخته می‌شود
     */
    fun priceWindow(
        lows: List<Double>,
        highs: List<Double>,
        overlays: List<Double> = emptyList(),
        extras: List<Double> = emptyList(),
        overlayBands: Double = DEFAULT_OVERLAY_BANDS,
        extraBands: Double = 8.0,
        fallback: Double? = null,
    ): Window {
        var cMin = Double.POSITIVE_INFINITY
        var cMax = Double.NEGATIVE_INFINITY
        for (i in lows.indices) {
            val low = lows.getOrNull(i) ?: continue
            val high = highs.getOrNull(i) ?: continue
            if (!low.isFinite() || !high.isFinite()) continue
            if (low <= 0.0 && high <= 0.0) continue
            if (low < cMin) cMin = low
            if (high > cMax) cMax = high
        }

        if (!cMin.isFinite() || !cMax.isFinite() || cMax < cMin) {
            val base = fallback?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val pad = base * 0.01
            return Window(base - pad, base + pad)
        }

        val candleSpan = (cMax - cMin).coerceAtLeast(cMax.coerceAtLeast(1e-9) * 1e-4)
        var minP = cMin
        var maxP = cMax

        fun absorb(value: Double, bands: Double) {
            if (!value.isFinite() || value <= 0.0) return
            if (value < cMin - candleSpan * bands) return
            if (value > cMax + candleSpan * bands) return
            if (value < minP) minP = value
            if (value > maxP) maxP = value
        }

        overlays.forEach { absorb(it, overlayBands) }
        extras.forEach { absorb(it, extraBands) }

        if (!minP.isFinite() || !maxP.isFinite() || maxP <= minP) {
            minP = cMin
            maxP = cMax + candleSpan
        }
        // آخرین محافظ: کادر باید همیشه متناهی و غیرصفر باشد، وگرنه `y = h - norm*h` می‌شود NaN
        // و بوم بی‌صدا خالی می‌ماند.
        if (!minP.isFinite() || !maxP.isFinite() || maxP <= minP) {
            val base = fallback?.takeIf { it.isFinite() && it > 0.0 } ?: cMax
            val pad = base * 0.01
            return Window(base - pad, base + pad)
        }
        return Window(minP, maxP)
    }

    /** کادر نمایش با حاشیهٔ ۱۰٪ بالا و پایین. */
    fun padded(window: Window, fraction: Double = 0.10): Window {
        val pad = window.span * fraction.coerceIn(0.0, 0.5)
        return Window(window.min - pad, window.max + pad)
    }
}
