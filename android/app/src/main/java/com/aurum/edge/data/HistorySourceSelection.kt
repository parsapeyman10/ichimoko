package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/** Selection evidence, not a promise of truth or permission to execute on another venue. */
data class HistorySourceAudit(val selected: String?, val checkedAt: Long, val note: String)
object HistorySourceAudits {
    private val mutable = MutableStateFlow<Map<String, HistorySourceAudit>>(emptyMap())
    val state = mutable.asStateFlow()
    internal fun record(symbol: String, selected: String?, note: String, now: Long) {
        mutable.update { it + (symbol to HistorySourceAudit(selected, now, note)) }
    }
}

internal object HistorySourceSelection {
    data class Candidate<T>(val value: T, val publisher: String, val frames: Map<Interval, List<Candle>>)
    data class Choice<T>(val selected: Candidate<T>, val note: String, val confirmations: Int = 1)

    /** Compare only the SAME closed M5 timestamp. Never average prices or stitch frames.
     * With two disagreeing publishers we cannot know which is right: fail closed. With
     * three, require a unique strict majority. A single source stays explicitly unconfirmed. */
    fun <T> choose(symbol: String, rows: List<Candidate<T>>, now: Long): Choice<T> {
        val valid = rows.distinctBy { it.publisher }.map { candidate ->
            candidate.copy(frames = candidate.frames.mapValues { (frame, bars) ->
                bars.filter { it.closed && it.time + frame.millis <= now }.sortedBy { it.time }.takeLast(320)
            })
        }.filter { candidate ->
            listOf(Interval.M5, Interval.M15, Interval.H1).all { frame ->
                CandleSourceHealth.ready(candidate.frames[frame].orEmpty(), frame, now,
                    requireContiguous = CryptoCatalog.isCrypto(symbol))
            }
        }
        if (valid.isEmpty()) throw DataFeedException("بستهٔ سالم و تازهٔ هم‌نماد برای مقایسه موجود نیست")
        require(valid.size <= 10) { "تعداد منابع برای مقایسه خارج از سقف معتبر است" }
        val freshest = compareBy<Candidate<T>> { it.frames.getValue(Interval.M5).last().time }
            .thenBy { it.frames.getValue(Interval.M15).last().time }
            .thenBy { it.frames.getValue(Interval.H1).last().time }
        if (valid.size == 1) return Choice(valid.single(), "فقط یک ناشر سالم؛ تأیید مستقل دوم موجود نیست")
        val shared = valid.map { it.frames.getValue(Interval.M5).map(Candle::time).toSet() }
            .reduce { a, b -> a intersect b }.maxOrNull()
        if (shared == null || now - (shared + Interval.M5.millis) !in 0..(Interval.M5.millis + 90_000L))
            throw SourceDisagreement("زمان مشترک تازه برای مقایسهٔ منابع موجود نیست؛ قیمت‌های ناهم‌زمان مقایسه نشدند")
        val tolerance = WatchCatalog.find(symbol)?.tolerancePct ?: if (symbol.contains("/USD") && symbol.startsWith("XA")) 0.5 else 0.3
        fun price(c: Candidate<T>) = c.frames.getValue(Interval.M5).first { it.time == shared }.close
        fun agree(a: Candidate<T>, b: Candidate<T>): Boolean = abs(price(a) - price(b)) / ((price(a) + price(b)) / 2) * 100 <= tolerance
        // At most four currently supported independent publishers per exact history route.
        // Enumerate pairwise-consistent groups; a chain A~B~C is not an A~C agreement.
        val groups = (1 until (1 shl valid.size)).map { mask -> valid.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }
            .filter { group -> group.all { a -> group.all { b -> agree(a, b) } } }
        val maxSize = groups.maxOf { it.size }
        val largest = groups.filter { it.size == maxSize }
        if (maxSize < 2 || maxSize * 2 <= valid.size || largest.size != 1)
            throw SourceDisagreement("اختلاف منابع هم‌زمان بیش از $tolerance٪ است؛ منبع صحیح قابل تشخیص نیست، تحلیل/ورود متوقف شد")
        val cluster = largest.single()
        val winner = cluster.maxWith(freshest)
        return Choice(winner, "${cluster.size} از ${valid.size} ناشر سالم در کندل هم‌زمان همخوان‌اند؛ " +
            "انتخاب تازه‌ترین بستهٔ کامل، بدون میانگین/ادغام" +
            (if (cluster.size < valid.size) "؛ منبع پرت کنار گذاشته شد" else ""), cluster.size)
    }
}
