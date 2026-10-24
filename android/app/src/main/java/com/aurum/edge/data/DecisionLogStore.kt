package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Signal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

@Serializable
data class DecisionLayer(val name: String, val points: Int?, val status: String, val detail: String)

@Serializable
data class DecisionRecord(
    val symbol: String,
    val interval: String,
    val checkedAt: Long,
    val barTime: Long,
    val action: String,
    val score: Double?,
    val reason: String,
    val layers: List<DecisionLayer> = emptyList(),
    val method: String? = null,
    val methodReason: String? = null,
    /** Only a model identifier, never a credential or raw prompt. Null for technical scans. */
    val aiModel: String? = null,
)

/** Bounded on-device audit trail. Records blocked/no-data attempts as well as candidates.
 * Passwords, model prompts, API keys and raw feed URLs are deliberately not stored here.
 *
 * Every read and every write is serialised on ONE background thread.
 *
 * Why: [append] used to encode the whole (up to 500 records) list to JSON and then block on
 * `SharedPreferences.commit()` — a synchronous, fsync-backed full-file rewrite — on the caller's
 * thread. It is called from the radar sweep (once per symbol per stage), from the market feed
 * gate and from the notification path, so a single sweep could issue dozens of blocking disk
 * writes, each re-serialising the entire trail. Moving the work to one daemon thread keeps the
 * audit identical and removes it from the feed/scanner/UI threads.
 */
class DecisionLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v1_decision_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _records = MutableStateFlow<List<DecisionRecord>>(emptyList())
    val records: StateFlow<List<DecisionRecord>> = _records.asStateFlow()

    /** Single-threaded so reads and writes can never interleave; a daemon so it never blocks exit. */
    private val worker = Executors.newSingleThreadExecutor(object : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "aurum-decision-log").apply { isDaemon = true }
    })
    init {
        // Submitted first, so every [append] that follows in submission order sees the loaded trail.
        submit {
            val loaded = runCatching {
                json.decodeFromString<List<DecisionRecord>>(prefs.getString("records", "[]") ?: "[]")
            }.getOrDefault(emptyList())
            _records.value = loaded
        }
    }

    private fun submit(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shutting down: dropping an audit line is correct, blocking the feed is not.
        }
    }

    fun append(symbol: String, interval: String, status: String, detail: String,
               signal: Signal? = null, now: Long = System.currentTimeMillis(),
               dedupe: Boolean = false, method: String? = null, methodReason: String? = null,
               barTimeOverride: Long? = null, aiModel: String? = null) {
        if (symbol.isBlank()) return
        submit {
            appendOnWorker(symbol, interval, status, detail, signal, now, dedupe,
                method, methodReason, barTimeOverride, aiModel)
        }
    }

    private fun appendOnWorker(symbol: String, interval: String, status: String, detail: String,
                               signal: Signal?, now: Long, dedupe: Boolean, method: String?,
                               methodReason: String?, barTimeOverride: Long?, aiModel: String?) {
        val layers = signal?.confluence?.take(4)?.map { it.toLayer() }.orEmpty()
        val item = DecisionRecord(symbol, interval, now, barTimeOverride ?: signal?.barTime ?: 0L,
            signal?.action?.name ?: status, signal?.confidence, detail.take(500), layers,
            method, methodReason?.take(300), aiModel?.take(80))
        val previous = _records.value
        // A closed market has no feed request or signal evaluation. A minute-by-minute
        // background sweep must not evict real decisions from the bounded audit trail.
        if (status == "closed" && previous.any {
                it.symbol == symbol && it.action == "closed" &&
                    now - it.checkedAt in 0L..30 * 60_000L
            }) return
        if (dedupe && previous.take(20).any {
                it.symbol == symbol && it.barTime == item.barTime && it.action == item.action &&
                    it.reason == item.reason && it.layers == layers && it.method == method &&
                    it.methodReason == item.methodReason && it.aiModel == item.aiModel &&
                    now - it.checkedAt in 0L..60_000L
            }) return
        val next = (listOf(item) + previous).take(500)
        // apply() is asynchronous but durable: Android queues the write and flushes it before
        // the process is killed, which is exactly the guarantee an audit trail needs. commit()
        // would block the worker thread on an fsync for no additional safety here.
        prefs.edit().putString("records", json.encodeToString(next)).apply()
        _records.value = next
    }

    private fun ConfluenceItem.toLayer() = DecisionLayer(name, scorePercent, status.name, detail.take(300))
}
