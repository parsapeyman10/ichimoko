package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Historical provider data for offline DISPLAY only, never evidence of a live quote. */
class CandleCache(context: Context) {
    private val dir = File(context.filesDir, "market").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Candle.serializer())

    private fun file(symbol: String, interval: Interval): File =
        // Older APKs saved unverified REST bars in the unversioned files. Do not silently
        // migrate them into a trusted cache under a new APK; reacquire them from the provider.
        File(dir, "verified_v2_${symbol.replace("/", "_")}_${interval.label}.json")

    internal fun verify(candles: List<Candle>, interval: Interval, now: Long = System.currentTimeMillis()): List<Candle> {
        require(candles.size <= MAX_BARS) { "تعداد کندل ذخیره‌شده معتبر نیست" }
        val seen = mutableSetOf<Long>()
        candles.forEach { bar ->
            require(bar.time > 0L && bar.time <= now + 60_000L && bar.time % interval.millis == 0L && seen.add(bar.time) &&
                listOf(bar.open, bar.high, bar.low, bar.close).all { it.isFinite() && it > 0.0 } &&
                bar.volume.isFinite() && bar.volume >= 0.0 &&
                bar.low <= minOf(bar.open, bar.close) && bar.high >= maxOf(bar.open, bar.close)) {
                "کندل کش زمان، قیمت یا حجم معتبر ندارد"
            }
        }
        return candles.sortedBy { it.time }
    }

    suspend fun load(symbol: String, interval: Interval): List<Candle> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = AtomicFile(file(symbol, interval)).openRead().use { stream ->
                stream.readBytes().also { require(it.size <= MAX_FILE_BYTES) }
            }
            verify(json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)), interval)
        }.getOrDefault(emptyList()) // corrupt/missing cache never becomes a quote or an entry
    }

    suspend fun save(symbol: String, interval: Interval, candles: List<Candle>) = withContext(Dispatchers.IO) {
        val verified = verify(candles.sortedBy { it.time }.takeLast(MAX_BARS), interval)
        val bytes = json.encodeToString(serializer, verified).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "حجم کش کندل بیش از حد است" }
        val atomic = AtomicFile(file(symbol, interval))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error // keep the previously verified cache intact on power/storage failure
        }
    }

    /** Merge only bars that still pass the same verification as newly fetched provider data. */
    suspend fun merge(symbol: String, interval: Interval, incoming: List<Candle>): List<Candle> {
        if (incoming.isEmpty()) return load(symbol, interval)
        val existing = load(symbol, interval).associateBy { it.time }.toMutableMap()
        verify(incoming, interval).forEach { existing[it.time] = it }
        val merged = verify(existing.values.sortedBy { it.time }.takeLast(MAX_BARS), interval)
        save(symbol, interval, merged)
        return merged
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_BARS = 3000
        private const val MAX_FILE_BYTES = 2_000_000
    }
}
