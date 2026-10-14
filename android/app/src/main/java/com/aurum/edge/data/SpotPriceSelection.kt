package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlin.math.abs

/** Exact XAU/USD spot quotes only; no futures, fabricated midpoint or consensus fill. */
internal object SpotPriceSelection {
    fun gold(quotes: List<PriceTick>, now: Long): PriceTick {
        val valid = quotes.filter { it.provider in setOf("Swissquote", "Gold-API") &&
            it.price.isFinite() && it.price > 0 && now - it.at in -10_000L..45_000L &&
            (it.bid == null || it.ask?.let { ask -> it.bid.isFinite() && ask.isFinite() && it.bid > 0 && ask >= it.bid } == true)
        }.distinctBy { it.provider }
        if (valid.isEmpty()) throw DataFeedException("هیچ قیمت تازهٔ XAU/USD از Swissquote/Gold-API دریافت نشد")
        if (valid.size == 1) return valid.single().copy(sourceNote = "فقط یک منبع قیمت تازه؛ تأیید مستقل دوم نداریم")
        val a = valid[0]; val b = valid[1]
        if (abs(a.at - b.at) > 10_000L) return valid.maxBy { it.at }.copy(
            sourceNote = "انتخاب قیمت تازه‌تر؛ اختلاف زمان ناشران اجازهٔ تأیید دومنبعی نمی‌دهد")
        val difference = abs(a.price - b.price) / ((a.price + b.price) / 2) * 100
        if (difference > 0.5) throw DataFeedException("قیمت‌های هم‌زمان طلا بیش از ۰٫۵٪ اختلاف دارند؛ قیمت قابل اتکا انتخاب نشد")
        // Preserve an actual publisher's entire quote, including original timestamp/bid/ask.
        // Prefer real BBO, then timestamp. Do not average feeds into a fictitious trade.
        return valid.maxWith(compareBy<PriceTick> { it.bid != null && it.ask != null }.thenBy { it.at })
            .copy(sourceNote = "دو منبع طلا با زمان نزدیک همخوان‌اند؛ قیمت واقعی ناشر دارای bid/ask اولویت دارد، نه میانگین؛ تضمین صحت نیست")
    }
}
