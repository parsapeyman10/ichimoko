package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide OPEN crypto position monitor. Unlike the quote-only screen feed, replay every
 * executed trade in each overlapping public page, in exchange order. No REST endpoint with
 * missing trade IDs can promise zero loss across a page rollover, app death or network gap.
 */
class OpenTradeTickMonitor(
    private val journal: JournalStore,
    private val spot: SpotFallbackClient = SpotFallbackClient(),
) {
    private data class Session(val openedAt: Long, val cursor: TradePageCursor = TradePageCursor(),
                               var lastReceiptAt: Long = 0L, var lastRequestAt: Long = 0L)
    private val sessions = mutableMapOf<String, Session>()
    private val mutex = Mutex()

    /** Null when the current batch is unavailable; never promote a stale quote. */
    suspend fun poll(symbol: String, openedAt: Long): PriceTick? = mutex.withLock {
        val now = System.currentTimeMillis()
        val session = sessions[symbol]?.takeIf { it.openedAt == openedAt }
            ?: Session(openedAt).also { sessions[symbol] = it }
        // REST trade endpoint allows 60 requests/minute; one shared body is reused by the
        // selected market and the foreground service. Avoid a pointless 1s busy loop.
        if (now - session.lastRequestAt in 0L until 10_000L) return@withLock null
        session.lastRequestAt = now
        val page = try {
            spot.fetchCryptoTrades(symbol)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (now - (session.lastReceiptAt.takeIf { it > 0 } ?: openedAt) > 45_000L)
                journal.noteTickCoverageGap(symbol, now)
            return@withLock null
        }
        val update = session.cursor.advance(page, openedAt)
        session.lastReceiptAt = now
        if (update.gap) journal.noteTickCoverageGap(symbol, now)
        // If a page did not overlap the last one, unknown trades are NOT replayed as
        // if the page were complete. Later overlapping pages resume known-only ticks.
        for (trade in update.newlySeen) {
            if (journal.settleTick(symbol, trade.price, trade.at).isNotEmpty()) {
                sessions.remove(symbol)
                break
            }
        }
        page.last().tick
    }
}
