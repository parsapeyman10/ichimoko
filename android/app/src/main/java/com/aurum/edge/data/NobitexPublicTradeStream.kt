package com.aurum.edge.data

import com.aurum.edge.core.TradeQuotePolicy
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.SubscribedEvent
import io.github.centrifugal.centrifuge.SubscribingEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.SubscriptionOptions
import io.github.centrifugal.centrifuge.UnsubscribedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

/** The official Centrifugo client handles application-level ping/pong, reconnect backoff and
 * recoverable subscriptions. Each OPEN pair gets a subscription (not a Binance ticker), and
 * callbacks only enqueue: parsing and journal writes must NOT block its network executor.
 */
internal class NobitexPublicTradeStream {
    sealed interface Event {
        data class Subscribed(val wasRecovering: Boolean, val recovered: Boolean,
                              val recoverable: Boolean) : Event
        data object Subscribing : Event
        data object Unsubscribed : Event
        data class Publication(val data: String, val offset: Long) : Event
        data object Invalid : Event
    }

    fun open(symbol: String, emit: (Event) -> Unit): Closeable {
        val ticker = NobitexHistoryClient.ticker(symbol)
            ?.takeIf { CryptoCatalog.isCrypto(symbol) }
            ?: throw DataFeedException("کانال معاملات این نماد موجود نیست")
        val options = Options().apply { setOkHttpClient(AppNetwork.builder().build()) }
        val client = Client("wss://ws.nobitex.ir/connection/websocket", options, object : EventListener() {})
        try {
            val subscription = client.newSubscription("public:trades-$ticker", SubscriptionOptions(),
                object : SubscriptionEventListener() {
                    override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                        emit(Event.Subscribed(event.wasRecovering() == true, event.recovered == true,
                            event.recoverable == true))
                    }
                    override fun onSubscribing(sub: Subscription, event: SubscribingEvent) {
                        emit(Event.Subscribing)
                    }
                    override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                        emit(Event.Unsubscribed)
                    }
                    override fun onPublication(sub: Subscription, event: PublicationEvent) {
                        // Limit decoded network data before it enters a bounded app queue.
                        val bytes = event.data
                        if (bytes == null || bytes.size > 8_192) emit(Event.Invalid)
                        else emit(Event.Publication(bytes.toString(Charsets.UTF_8), event.offset))
                    }
                })
            subscription.subscribe()
            client.connect()
        } catch (error: Exception) {
            client.close(0)
            throw error
        }
        return Closeable { client.close(1_000) }
    }
}

/** Wire contract from the Nobitex public trades channel: milliseconds, no trade ID in data.
 * The transport's publication offset is only usable if the server actually enables recovery.
 */
internal object NobitexStreamTrade {
    fun parse(body: String, now: Long = System.currentTimeMillis()): NobitexTradeBatch.Trade {
        val row = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پیام معاملات زنده نامعتبر است")
        val at = row["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw DataFeedException("زمان معاملهٔ زنده نامعتبر است")
        val price = row["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw DataFeedException("قیمت معاملهٔ زنده نامعتبر است")
        val volume = row["volume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw DataFeedException("حجم معاملهٔ زنده نامعتبر است")
        val side = row["type"]?.jsonPrimitive?.contentOrNull
            ?: throw DataFeedException("جهت معاملهٔ زنده نامعتبر است")
        val trade = NobitexTradeBatch.Trade(at, price, volume, side)
        if (volume < 0 || !volume.isFinite() || side !in setOf("buy", "sell") ||
            !TradeQuotePolicy.accepts(trade.tick, now))
            throw DataFeedException("معاملهٔ زنده کهنه یا نامعتبر است")
        return trade
    }
}

/** Pure per-position continuity checks; a missing offset is NOT a made-up sequence number.
 * Even a server-reported recovery is only valid inside the server's retained history.
 */
internal class TradeStreamCursor(private val openedAt: Long) {
    enum class Verdict { APPLY, SKIP, GAP, GAP_APPLY }
    private var everSubscribed = false
    private var recoverable = false
    private var lastOffset: Long? = null
    private var lastAt = 0L
    private var lastSourceRest = false

    fun subscribed(event: NobitexPublicTradeStream.Event.Subscribed): Boolean {
        val gap = if (everSubscribed) !event.wasRecovering || !event.recovered
            else true // No ID ties entry/REST to first socket publication, even if REST failed.
        if (gap || !event.recovered) lastOffset = null
        recoverable = event.recoverable
        everSubscribed = true
        return gap
    }

    fun rest(at: Long): Boolean {
        // A REST page can arrive after socket settlement (including after the service
        // was cancelled). Never replay an older/same-ms execution over a trailing stop.
        if (at <= lastAt) return false
        lastAt = at
        lastSourceRest = true
        return true
    }

    fun publication(at: Long, offset: Long): Verdict {
        if (at < openedAt) return Verdict.SKIP
        // REST has no shared sequence identity. A same/older-millisecond socket row
        // could already have been settled by REST: audit the uncertainty, don't replay.
        if (lastSourceRest && at <= lastAt) return Verdict.GAP
        if (recoverable) {
            if (offset <= 0) {
                lastAt = maxOf(lastAt, at)
                lastOffset = null
                lastSourceRest = false
                return Verdict.GAP_APPLY
            }
            val previous = lastOffset
            if (previous != null && offset <= previous) return Verdict.SKIP
            val skipped = previous != null && offset > previous + 1
            lastOffset = offset
            // Provider execution milliseconds are NOT the transport sequence. A trade
            // can arrive with an earlier millisecond but a newer ordered stream offset.
            // Keep the highest time for REST handoff, process in publisher offset order.
            lastAt = maxOf(lastAt, at)
            lastSourceRest = false
            return if (skipped) Verdict.GAP_APPLY else Verdict.APPLY
        }
        // A non-recoverable channel exposes no trustworthy sequence. Do not replay
        // older timestamped executions on top of a newer trailing stop.
        if (at < lastAt) return Verdict.GAP
        lastAt = at
        lastSourceRest = false
        return Verdict.APPLY
    }
}
