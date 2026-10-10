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
)

/** Bounded on-device audit trail. Records blocked/no-data attempts as well as candidates.
 * Passwords, model prompts, API keys and raw feed URLs are deliberately not stored here.
 */
class DecisionLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v1_decision_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _records = MutableStateFlow(runCatching {
        json.decodeFromString<List<DecisionRecord>>(prefs.getString("records", "[]") ?: "[]")
    }.getOrDefault(emptyList()))
    val records: StateFlow<List<DecisionRecord>> = _records.asStateFlow()

    @Synchronized
    fun append(symbol: String, interval: String, status: String, detail: String,
               signal: Signal? = null, now: Long = System.currentTimeMillis(),
               dedupe: Boolean = false, method: String? = null, methodReason: String? = null) {
        if (symbol.isBlank()) return
        val layers = signal?.confluence?.take(4)?.map { it.toLayer() }.orEmpty()
        val item = DecisionRecord(symbol, interval, now, signal?.barTime ?: 0L,
            signal?.action?.name ?: status, signal?.confidence, detail.take(500), layers,
            method, methodReason?.take(300))
        val previous = _records.value
        if (dedupe && previous.take(20).any {
                it.symbol == symbol && it.barTime == item.barTime && it.action == item.action &&
                    it.reason == item.reason && it.layers == layers && it.method == method &&
                    it.methodReason == item.methodReason && now - it.checkedAt in 0L..60_000L
            }) return
        val next = (listOf(item) + previous).take(500)
        if (prefs.edit().putString("records", json.encodeToString(next)).commit()) _records.value = next
    }

    private fun ConfluenceItem.toLayer() = DecisionLayer(name, scorePercent, status.name, detail.take(300))
}
