package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide gate for the same Nobitex venue and one open-position generation.
 * WebSocket publications are the primary ordered path while the user-enabled foreground
 * service runs. REST is an overlapping-page bootstrap/fallback, never a different exchange.
 * Neither a socket nor a finite REST page is proof of all trades across Android process death.
 */
class OpenTradeTickMonitor(
    private val journal: JournalStore,
    private val spot: SpotFallbackClient = SpotFallbackClient(),
) {
    enum class Feed { CONNECTING, STREAM, STREAM_RECOVERABLE, RECOVERING, REST_FALLBACK }
    private data class Session(
        val openedAt: Long,
        val page: TradePageCursor = TradePageCursor(),
        val stream: TradeStreamCursor = TradeStreamCursor(openedAt),
        val mutex: Mutex = Mutex(),
        val watcher: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connected: Boolean = false,
        @Volatile var latest: PriceTick? = null,
        var lastReceiptAt: Long = 0L,
        var lastRequestAt: Long = 0L,
        var disconnectedAt: Long = 0L,
    )
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val _feeds = MutableStateFlow<Map<String, Feed>>(emptyMap())
    val feeds: StateFlow<Map<String, Feed>> = _feeds.asStateFlow()

    private fun setFeed(symbol: String, session: Session, feed: Feed?) {
        if (sessions[symbol] !== session) return
        _feeds.update { old -> old.toMutableMap().apply {
            if (feed == null) remove(symbol) else put(symbol, feed)
        } }
    }

    private fun session(symbol: String, openedAt: Long): Session = sessions.compute(symbol) { _, old ->
        old?.takeIf { it.openedAt == openedAt } ?: Session(openedAt)
    }!!

    private fun stillOpen(symbol: String, session: Session): Boolean =
        sessions[symbol] === session && journal.trades.value.any {
            it.isOpen && it.symbol == symbol && it.openedAt == session.openedAt
        }

    private fun forget(symbol: String, session: Session) {
        if (sessions.remove(symbol, session)) setFeedRemoved(symbol)
    }

    private fun setFeedRemoved(symbol: String) {
        _feeds.update { it - symbol }
    }

    /** Called by the visible screen (or as REST bootstrap/fallback by the position worker).
     * While the stream is healthy, the screen sees only its LAST FRESH received trade and
     * never starts a parallel REST replay that could reorder a trailing stop.
     */
    suspend fun poll(symbol: String, openedAt: Long): PriceTick? = pollPage(symbol, openedAt, bootstrap = false)

    private suspend fun pollPage(symbol: String, openedAt: Long, bootstrap: Boolean): PriceTick? {
        val session = session(symbol, openedAt)
        return session.mutex.withLock {
            if (!stillOpen(symbol, session)) {
                forget(symbol, session)
                return@withLock null
            }
            val now = System.currentTimeMillis()
            if (session.watcher.get() && !bootstrap) {
                if (session.connected || now - session.disconnectedAt < 10_000L)
                    return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
                setFeed(symbol, session, Feed.REST_FALLBACK)
                // REST after a lost socket is only a best-effort fallback: it cannot prove
                // the missing segment even if the pages themselves overlap.
                journal.noteTickCoverageGap(symbol, now, openedAt)
            }
            if (now - session.lastRequestAt in 0L until 3_000L)
                return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
            session.lastRequestAt = now
            val page = try {
                spot.fetchCryptoTrades(symbol, openedAt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (now - (session.lastReceiptAt.takeIf { it > 0 } ?: openedAt) > 45_000L)
                    journal.noteTickCoverageGap(symbol, now, openedAt)
                return@withLock null
            }
            val update = session.page.advance(page, openedAt)
            session.lastReceiptAt = now
            if (update.gap) journal.noteTickCoverageGap(symbol, now, openedAt)
            // No fresh publication is ever replaced with an older page's last row.
            for (trade in update.newlySeen) {
                // REST may contain publications already settled before the socket dropped.
                // Its rows have no IDs: never replay a past/same-ms page over a newer stop.
                if (!session.stream.rest(trade.at)) {
                    journal.noteTickCoverageGap(symbol, now, openedAt)
                    continue
                }
                if (journal.settleTick(symbol, trade.price, trade.at, expectedOpenedAt = openedAt).isNotEmpty()) {
                    forget(symbol, session)
                    break
                }
            }
            val tick = page.last().tick
            if (session.latest == null || tick.at > session.latest!!.at) session.latest = tick
            session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
        }
    }

    /** Own one cancellable stream per open crypto position, separate from scan/AI/news.
     * Centrifugo's SDK reconnects and requests recovery only if the server advertised it.
     * A bounded callback queue makes overload observable rather than silently dropping ticks.
     */
    suspend fun watch(symbol: String, openedAt: Long) {
        val session = session(symbol, openedAt)
        if (!session.watcher.compareAndSet(false, true)) return
        val events = Channel<NobitexPublicTradeStream.Event>(2_048)
        val overflow = AtomicBoolean(false)
        var socket: java.io.Closeable? = null
        try {
            setFeed(symbol, session, Feed.CONNECTING)
            // Bootstrap REST while the socket is not yet subscribed. The gap between
            // these sources is explicitly audited on first subscribe, not fabricated.
            pollPage(symbol, openedAt, bootstrap = true)
            if (!stillOpen(symbol, session)) return
            session.disconnectedAt = System.currentTimeMillis()
            socket = try {
                NobitexPublicTradeStream().open(symbol) { event ->
                    if (!events.trySend(event).isSuccess) overflow.set(true)
                }
            } catch (_: Exception) {
                // A blocked WebSocket does not disable same-venue REST fallback.
                null
            }
            var lastSocketAttempt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && stillOpen(symbol, session)) {
                val event = withTimeoutOrNull(3_000L) { events.receive() }
                val now = System.currentTimeMillis()
                if (overflow.getAndSet(false)) journal.noteTickCoverageGap(symbol, now, openedAt)
                when (event) {
                    is NobitexPublicTradeStream.Event.Subscribed -> session.mutex.withLock {
                        if (!stillOpen(symbol, session)) return@withLock
                        if (session.stream.subscribed(event))
                            journal.noteTickCoverageGap(symbol, now, openedAt)
                        session.connected = true
                        session.disconnectedAt = 0L
                        setFeed(symbol, session, if (event.recoverable) Feed.STREAM_RECOVERABLE else Feed.STREAM)
                    }
                    NobitexPublicTradeStream.Event.Subscribing,
                    NobitexPublicTradeStream.Event.Unsubscribed -> {
                        session.connected = false
                        if (session.disconnectedAt == 0L) session.disconnectedAt = now
                        setFeed(symbol, session, Feed.RECOVERING)
                    }
                    is NobitexPublicTradeStream.Event.Publication -> session.mutex.withLock {
                        if (!stillOpen(symbol, session)) return@withLock
                        val trade = try { NobitexStreamTrade.parse(event.data, now) }
                        catch (_: Exception) {
                            journal.noteTickCoverageGap(symbol, now, openedAt)
                            return@withLock
                        }
                        when (session.stream.publication(trade.at, event.offset)) {
                            TradeStreamCursor.Verdict.SKIP -> return@withLock
                            TradeStreamCursor.Verdict.GAP -> {
                                journal.noteTickCoverageGap(symbol, now, openedAt)
                                return@withLock
                            }
                            TradeStreamCursor.Verdict.GAP_APPLY ->
                                journal.noteTickCoverageGap(symbol, now, openedAt)
                            TradeStreamCursor.Verdict.APPLY -> Unit
                        }
                        session.lastReceiptAt = now
                        session.latest = trade.tick
                        if (journal.settleTick(symbol, trade.price, trade.at, expectedOpenedAt = openedAt).isNotEmpty())
                            forget(symbol, session)
                    }
                    NobitexPublicTradeStream.Event.Invalid -> journal.noteTickCoverageGap(symbol, now, openedAt)
                    null -> Unit
                }
                if (!session.connected) {
                    if (socket == null && now - lastSocketAttempt >= 30_000L) {
                        lastSocketAttempt = now
                        socket = runCatching {
                            NobitexPublicTradeStream().open(symbol) { event ->
                                if (!events.trySend(event).isSuccess) overflow.set(true)
                            }
                        }.getOrNull()
                    }
                    if (session.disconnectedAt == 0L) session.disconnectedAt = now
                    if (now - session.disconnectedAt > 45_000L)
                        journal.noteTickCoverageGap(symbol, now, openedAt)
                    // If WebSocket cannot connect, keep processing the same-venue REST pages.
                    if (now - session.disconnectedAt >= 10_000L) poll(symbol, openedAt)
                }
            }
        } finally {
            session.watcher.set(false)
            session.connected = false
            events.close()
            withContext(NonCancellable + Dispatchers.IO) { runCatching { socket?.close() } }
            if (sessions[symbol] === session) setFeedRemoved(symbol)
        }
    }
}
