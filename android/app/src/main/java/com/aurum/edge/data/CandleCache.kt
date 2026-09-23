package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache of *real* candles received from the provider.
 *
 * Purpose: when the phone has no internet, the app shows the last real bars it actually
 * received, clearly labelled with their timestamp — never a simulation.
 */
class CandleCache(context: Context) {

    private val dir = File(context.filesDir, "market").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(symbol: String, interval: Interval): File =
        File(dir, "${symbol.replace("/", "_")}_${interval.label}.json")

    suspend fun load(symbol: String, interval: Interval): List<Candle> = withContext(Dispatchers.IO) {
        val f = file(symbol, interval)
        if (!f.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(Candle.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun save(symbol: String, interval: Interval, candles: List<Candle>) = withContext(Dispatchers.IO) {
        val f = file(symbol, interval)
        val trimmed = candles.takeLast(MAX_BARS)
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Candle.serializer()), trimmed))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /** Merge freshly fetched bars into the cache; returns the merged, de-duplicated series. */
    suspend fun merge(symbol: String, interval: Interval, incoming: List<Candle>): List<Candle> {
        if (incoming.isEmpty()) return load(symbol, interval)
        val existing = load(symbol, interval).associateBy { it.time }.toMutableMap()
        incoming.forEach { existing[it.time] = it }
        val merged = existing.values.sortedBy { it.time }.takeLast(MAX_BARS)
        save(symbol, interval, merged)
        return merged
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_BARS = 3000
    }
}
