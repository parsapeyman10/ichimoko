package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One REAL order this app asked Nobitex to place, with real money, on the user's own account.
 * This is a local ledger of requests/responses only — Nobitex's own account history is the
 * source of truth. Kept completely separate from the paper [JournalStore] and from
 * [NobitexPracticeStore]: real fills must never be counted into paper win-rate/statistics.
 */
@Serializable
data class NobitexLiveOrder(
    val id: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val execution: String,
    val requestedAmount: String,
    val requestedPrice: String?,
    val notionalCapUsdt: Double,
    val submittedAt: Long,
    val exchangeOrderId: Long? = null,
    val lastStatus: String = "در انتظار پاسخ نوبیتکس",
    val lastCheckedAt: Long? = null,
    val lastMatchedAmount: String? = null,
    val rawLastResponse: String? = null,
    val errorMessage: String? = null,
) {
    val isTerminal: Boolean get() = lastStatus in setOf("Done", "Canceled", "ثبت نشد")
}

/**
 * Separate AtomicFile ledger for REAL Nobitex orders. Never merged with the paper journal
 * (gold or crypto) or its win-rate/profit-factor statistics — a real fill is not a backtest
 * sample and must not silently distort them.
 */
class NobitexLiveTradeStore(
    context: Context,
    private val file: File = File(context.filesDir, "nobitex_live_orders.json"),
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val _orders = MutableStateFlow<List<NobitexLiveOrder>>(emptyList())
    val orders: StateFlow<List<NobitexLiveOrder>> = _orders.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val parsed = try {
                if (file.exists() || File(file.path + ".bak").exists()) {
                    json.decodeFromString(ListSerializer(NobitexLiveOrder.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                } else emptyList()
            } catch (error: Exception) {
                _loadError.value = "دفتر سفارش‌های واقعی نوبیتکس خوانده نشد؛ برای حفظ سوابق، ثبت جدید متوقف است"
                throw IllegalStateException(_loadError.value, error)
            }
            _orders.value = parsed.sortedByDescending { it.submittedAt }
            _loadError.value = null
            loaded = true
        }
    }

    private suspend fun persist(next: List<NobitexLiveOrder>) = withContext(Dispatchers.IO) {
        check(loaded && _loadError.value == null) { "دفتر سفارش‌های واقعی نوبیتکس خوانده نشده است" }
        val kept = next.sortedByDescending { it.submittedAt }.take(500)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(ListSerializer(NobitexLiveOrder.serializer()), kept).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        _orders.value = kept
    }

    /** Records the outcome of a placement attempt (success OR failure) — never silently dropped. */
    suspend fun recordAttempt(
        symbol: String, side: String, execution: String, amount: String, price: String?,
        notionalCapUsdt: Double, clientOrderId: String, result: Result<NobitexOrderResult>,
        now: Long = System.currentTimeMillis(),
    ): NobitexLiveOrder = mutex.withLock {
        check(loaded && _loadError.value == null) { "دفتر سفارش‌های واقعی نوبیتکس آماده نیست" }
        val order = result.fold(
            onSuccess = { r ->
                NobitexLiveOrder(
                    id = UUID.randomUUID().toString(), clientOrderId = clientOrderId, symbol = symbol,
                    side = side, execution = execution, requestedAmount = amount, requestedPrice = price,
                    notionalCapUsdt = notionalCapUsdt, submittedAt = now,
                    exchangeOrderId = r.id, lastStatus = r.status, lastCheckedAt = now,
                    lastMatchedAmount = r.matchedAmount, rawLastResponse = r.raw,
                )
            },
            onFailure = { e ->
                NobitexLiveOrder(
                    id = UUID.randomUUID().toString(), clientOrderId = clientOrderId, symbol = symbol,
                    side = side, execution = execution, requestedAmount = amount, requestedPrice = price,
                    notionalCapUsdt = notionalCapUsdt, submittedAt = now,
                    lastStatus = "ثبت نشد", lastCheckedAt = now,
                    errorMessage = e.message ?: "خطای نامشخص هنگام ارسال سفارش",
                )
            },
        )
        persist(_orders.value + order)
        order
    }

    suspend fun updateStatus(id: String, result: Result<NobitexOrderResult>,
                             now: Long = System.currentTimeMillis()): NobitexLiveOrder = mutex.withLock {
        check(loaded && _loadError.value == null) { "دفتر سفارش‌های واقعی نوبیتکس آماده نیست" }
        val existing = _orders.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("سفارش واقعی در دفتر محلی پیدا نشد")
        val updated = result.fold(
            onSuccess = { r -> existing.copy(lastStatus = r.status, lastCheckedAt = now,
                lastMatchedAmount = r.matchedAmount, rawLastResponse = r.raw,
                exchangeOrderId = r.id ?: existing.exchangeOrderId, errorMessage = null) },
            onFailure = { e -> existing.copy(lastCheckedAt = now, errorMessage = e.message ?: "خطا در دریافت وضعیت") },
        )
        persist(_orders.value.map { if (it.id == id) updated else it })
        updated
    }

    suspend fun clear() = mutex.withLock { persist(emptyList()) }
}
