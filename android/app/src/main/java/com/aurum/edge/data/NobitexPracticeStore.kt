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
import kotlin.math.floor
import kotlin.math.round

/** Spot BUY only. A quoted ask/bid is an observation, never a filled exchange order. */
@Serializable
data class NobitexPracticeTrade(
    val id: String,
    val symbol: String,
    val quoteUnit: String,
    val quantityBtc: Double,
    val entryAsk: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val notionalQuote: Double,
    val openedAt: Long,
    val quoteReceivedAt: Long,
    val historyBarTime: Long,
    val historyInterval: String,
    val conditionNote: String,
    val closedAt: Long? = null,
    val exitBid: Double? = null,
    val exitReason: String? = null,
    val pnlQuote: Double? = null,
) { val isOpen: Boolean get() = closedAt == null }

data class NobitexPracticePreview(
    val ask: Double, val quantityBtc: Double, val notional: Double,
    val stop: Double, val target: Double, val riskQuote: Double,
)

object NobitexPracticeRules {
    fun preview(snapshot: NobitexSnapshot, amountUsdt: Double, stopPercent: Double,
                targetPercent: Double, now: Long = System.currentTimeMillis()): NobitexPracticePreview {
        snapshot.practiceBlocker(now)?.let { throw IllegalArgumentException(it) }
        require(snapshot.market == NobitexMarket.BTC_USDT && amountUsdt.isFinite() && amountUsdt in 20.0..10_000.0) {
            "فقط BTCUSDT؛ بودجهٔ فرضی بین ۲۰ تا ۱۰٬۰۰۰ USDT باشد"
        }
        require(stopPercent.isFinite() && targetPercent.isFinite() &&
            stopPercent in 0.5..15.0 && targetPercent in 1.0..30.0 &&
            targetPercent / stopPercent in 1.5..5.0) {
            "حد ضرر ۰٫۵ تا ۱۵٪ و نسبت سود/ریسک ۱٫۵ تا ۵ لازم است"
        }
        val ask = snapshot.quote.bestSell
        val quantity = floor(amountUsdt / ask * 1_000_000.0) / 1_000_000.0
        require(quantity.isFinite() && quantity >= 0.000001) { "حجم تمرینی بسیار کوچک است" }
        val stop = ask * (1 - stopPercent / 100.0)
        val target = ask * (1 + targetPercent / 100.0)
        val notional = ask * quantity
        val risk = (ask - stop) * quantity
        require(notional.isFinite() && notional <= amountUsdt + 1e-8 &&
            risk.isFinite() && risk > 0) { "قیمت یا ریسک تمرینی معتبر نیست" }
        return NobitexPracticePreview(ask, quantity, notional, stop, target, risk)
    }
}

/** Separate AtomicFile ledger. Never mixed with USD XAU journal, 9/9 alerts, or its statistics. */
class NobitexPracticeStore(context: Context,
                           private val file: File = File(context.filesDir, "nobitex_spot_paper.json")) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val _trades = MutableStateFlow<List<NobitexPracticeTrade>>(emptyList())
    val trades: StateFlow<List<NobitexPracticeTrade>> = _trades.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val parsed = try {
                if (file.exists() || File(file.path + ".bak").exists()) {
                    json.decodeFromString(ListSerializer(NobitexPracticeTrade.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                } else emptyList()
            } catch (error: Exception) {
                _loadError.value = "تمرین نوبیتکس خوانده نشد؛ برای حفظ سوابق تمرین جدید متوقف است"
                throw IllegalStateException(_loadError.value, error)
            }
            _trades.value = parsed.sortedByDescending { it.openedAt }
            _loadError.value = null
            loaded = true
        }
    }

    private suspend fun persist(next: List<NobitexPracticeTrade>) = withContext(Dispatchers.IO) {
        check(loaded && _loadError.value == null) { "ژورنال تمرین نوبیتکس خوانده نشده است" }
        val kept = (next.filter { it.isOpen } +
            next.filterNot { it.isOpen }.sortedByDescending { it.openedAt }.take(200))
            .sortedByDescending { it.openedAt }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(ListSerializer(NobitexPracticeTrade.serializer()), kept).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        _trades.value = kept
    }

    suspend fun open(snapshot: NobitexSnapshot, amountUsdt: Double, stopPercent: Double,
                     targetPercent: Double, expectedAsk: Double, expectedQuoteAt: Long,
                     now: Long = System.currentTimeMillis()): NobitexPracticeTrade = mutex.withLock {
        check(loaded && _loadError.value == null) { "ژورنال تمرین نوبیتکس آماده نیست" }
        require(snapshot.market == NobitexMarket.BTC_USDT &&
            snapshot.quote.receivedAt == expectedQuoteAt && expectedAsk.isFinite() && expectedAsk > 0 &&
            kotlin.math.abs(snapshot.quote.bestSell / expectedAsk - 1.0) <= 0.001) {
            "قیمت/بازار از پیش‌نمایش تغییر کرده است"
        }
        val preview = NobitexPracticeRules.preview(snapshot, amountUsdt, stopPercent, targetPercent, now)
        require(_trades.value.none { it.isOpen && it.symbol == snapshot.market.code }) {
            "برای BTCUSDT از قبل تمرین باز دارید"
        }
        val trade = NobitexPracticeTrade(
            id = UUID.randomUUID().toString(), symbol = snapshot.market.code, quoteUnit = "USDT",
            quantityBtc = preview.quantityBtc, entryAsk = preview.ask,
            stopLoss = preview.stop, takeProfit = preview.target,
            notionalQuote = preview.notional, openedAt = now,
            quoteReceivedAt = snapshot.quote.receivedAt,
            historyBarTime = snapshot.lastClosed!!.time,
            historyInterval = snapshot.interval.label,
            conditionNote = "ورود دستی spot BUY کاغذی، قیمت ask عمومی؛ ۹ شرط طلا/AI بررسی نشده؛ کارمزد و لغزش لحاظ نشده‌اند",
        )
        persist(_trades.value + trade)
        trade
    }

    /** Only a later observed public bid can settle SL/TP; historical candles never invent fills. */
    suspend fun settle(snapshot: NobitexSnapshot, now: Long = System.currentTimeMillis()): List<NobitexPracticeTrade> = mutex.withLock {
        if (!loaded || snapshot.practiceBlocker(now) != null) return@withLock emptyList()
        val bid = snapshot.quote.bestBuy
        val closed = mutableListOf<NobitexPracticeTrade>()
        val next = _trades.value.map { trade ->
            if (!trade.isOpen || trade.symbol != snapshot.market.code ||
                snapshot.quote.receivedAt <= trade.openedAt ||
                (bid > trade.stopLoss && bid < trade.takeProfit)) return@map trade
            val result = closeAtBid(trade, bid, now,
                if (bid <= trade.stopLoss) "عبور قیمت خرید مشاهده‌شده از حد ضرر؛ تسویهٔ کاغذی" else
                    "عبور قیمت خرید مشاهده‌شده از حد سود؛ تسویهٔ کاغذی")
            closed += result
            result
        }
        if (closed.isNotEmpty()) persist(next)
        closed
    }

    suspend fun close(tradeId: String, snapshot: NobitexSnapshot,
                      now: Long = System.currentTimeMillis()): NobitexPracticeTrade = mutex.withLock {
        check(loaded && _loadError.value == null) { "ژورنال تمرین نوبیتکس آماده نیست" }
        snapshot.practiceBlocker(now)?.let { throw IllegalArgumentException(it) }
        val trade = _trades.value.singleOrNull { it.id == tradeId && it.isOpen && it.symbol == snapshot.market.code }
            ?: throw IllegalArgumentException("تمرین باز همین نماد پیدا نشد")
        require(snapshot.quote.receivedAt > trade.openedAt) { "برای خروج، آمار تازه‌تری از زمان ورود دریافت کنید" }
        val result = closeAtBid(trade, snapshot.quote.bestBuy, now, "بستن دستی کاغذی با نرخ bid مشاهده‌شده")
        persist(_trades.value.map { if (it.id == tradeId) result else it })
        result
    }

    suspend fun clear() = mutex.withLock { persist(emptyList()) }

    private fun closeAtBid(trade: NobitexPracticeTrade, bid: Double, now: Long, reason: String) = trade.copy(
        closedAt = now, exitBid = bid, exitReason = reason,
        pnlQuote = round((bid - trade.entryAsk) * trade.quantityBtc * 100.0) / 100.0,
    )
}
