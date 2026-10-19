package com.aurum.edge.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Sticky source promotion ("the source that answers IS the primary source").
 *
 * Each symbol starts with its dedicated source. When a source fails to deliver a
 * healthy candle bundle, the next candidate is tried immediately; the first source
 * that answers with healthy data is promoted to primary for that symbol and STAYS
 * primary (persisted across restarts) until it itself fails twice in a row.
 * A source with >= [DEMOTE_AFTER] consecutive failures is ordered last — but never
 * removed from the candidate list, so a recovered source can win its place back.
 *
 * Local admission back-pressure (PublisherOccupied) is NOT a source failure and
 * must never be recorded here.
 */
internal object SourcePromotion {
    const val DEMOTE_AFTER = 2

    enum class Kind { HISTORY, CRYPTO }

    private class Health {
        @Volatile var primary: String? = null
        val consecutiveFailures = ConcurrentHashMap<String, Int>()
        @Volatile var lastFailure: String? = null
    }

    private val health = ConcurrentHashMap<String, Health>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun key(symbol: String, kind: Kind) = "${symbol.trim().uppercase()}|${kind.name}"
    private fun healthOf(symbol: String, kind: Kind): Health =
        health.getOrPut(key(symbol, kind)) { Health() }

    /** Load persisted primaries once (AppContainer). Safe to call repeatedly. */
    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences("aurum_source_promotion", Context.MODE_PRIVATE)
        prefs = store
        store.all.forEach { (k, v) ->
            (v as? String)?.takeIf { it.isNotBlank() }?.let { primary ->
                health.getOrPut(k) { Health() }.primary = primary
            }
        }
    }

    private fun persist(symbol: String, kind: Kind, primary: String?) {
        val store = prefs ?: return
        val k = key(symbol, kind)
        store.edit().apply {
            if (primary == null) remove(k) else putString(k, primary)
        }.apply()
    }

    fun primaryId(symbol: String, kind: Kind): String? = healthOf(symbol, kind).primary

    fun failuresOf(symbol: String, kind: Kind, sourceId: String): Int =
        healthOf(symbol, kind).consecutiveFailures[sourceId] ?: 0

    /**
     * Candidate order for the next fetch: sticky primary first, then the caller's
     * natural order, with demoted (repeatedly failing) sources pushed to the end.
     */
    fun <T> ordered(symbol: String, kind: Kind, candidates: List<T>, id: (T) -> String): List<T> {
        if (candidates.size <= 1) return candidates
        val h = healthOf(symbol, kind)
        val primary = h.primary
        return candidates.sortedWith(compareBy(
            { candidate -> if (id(candidate) == primary) 0 else 1 },
            { candidate -> if ((h.consecutiveFailures[id(candidate)] ?: 0) >= DEMOTE_AFTER) 1 else 0 },
            { candidate -> candidates.indexOf(candidate) },
        ))
    }

    /** A healthy answer: this source becomes (or stays) the sticky primary. */
    fun onSuccess(symbol: String, kind: Kind, sourceId: String) {
        val h = healthOf(symbol, kind)
        h.consecutiveFailures[sourceId] = 0
        if (h.primary != sourceId) {
            h.primary = sourceId
            persist(symbol, kind, sourceId)
        }
    }

    /** A real delivery failure (network/parse/unhealthy/stale). Returns the new count. */
    fun onFailure(symbol: String, kind: Kind, sourceId: String, reason: String): Int {
        val h = healthOf(symbol, kind)
        val count = (h.consecutiveFailures[sourceId] ?: 0) + 1
        h.consecutiveFailures[sourceId] = count
        h.lastFailure = "$sourceId: ${reason.take(140)}"
        if (h.primary == sourceId && count >= DEMOTE_AFTER) {
            h.primary = null // next healthy answer takes over as primary
            persist(symbol, kind, null)
        }
        return count
    }

    /** Test-only: forget in-memory health/primaries (persistence is untouched). */
    internal fun resetForTests() { health.clear() }

    /** One-line human summary for audits/status (dead sources are announced, not hidden). */
    fun summaryFa(symbol: String, kind: Kind, labelOf: (String) -> String): String {
        val h = healthOf(symbol, kind)
        val dead = h.consecutiveFailures.entries
            .filter { it.value >= DEMOTE_AFTER }
            .joinToString("، ") { "${labelOf(it.key)} (${it.value} خطای متوالی)" }
        val primary = h.primary?.let { "منبع اصلی فعلی: ${labelOf(it)}" } ?: "منبع اصلی تثبیت نشده"
        return if (dead.isBlank()) primary else "$primary · منبع بی‌پاسخ: $dead"
    }
}
