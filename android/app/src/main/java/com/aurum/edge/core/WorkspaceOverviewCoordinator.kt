package com.aurum.edge.core

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fixed, read-only checks for the foreground overview. No trading engine, journal or service is started here. */
enum class OverviewCheck {
    FOREX_CALENDAR, FOREX_PUBLISHERS, FOREX_SAMPLE,
    CRYPTO_PRICES, CRYPTO_PUBLISHERS,
    NOBITEX_STATS, NOBITEX_NOTICES,
    IRAN_PUBLISHERS, IRAN_BOARD,
}

object WorkspaceOverviewSchedule {
    fun checks(now: Long): Set<OverviewCheck> = buildSet {
        // Scheduled closures prevent unnecessary PRICE requests. These windows are not a holiday calendar.
        if (!MarketHours.forexWeekendClosed(now)) {
            add(OverviewCheck.FOREX_CALENDAR)
            add(OverviewCheck.FOREX_PUBLISHERS)
            add(OverviewCheck.FOREX_SAMPLE)
        }
        add(OverviewCheck.CRYPTO_PRICES)
        add(OverviewCheck.CRYPTO_PUBLISHERS)
        add(OverviewCheck.NOBITEX_STATS)
        add(OverviewCheck.NOBITEX_NOTICES)
        // Publisher news can still arrive after the Iranian market closes; no board polling then.
        add(OverviewCheck.IRAN_PUBLISHERS)
        if (MarketHours.iranStockSessionScheduled(now)) add(OverviewCheck.IRAN_BOARD)
    }
}

/** Launches all eligible sources concurrently, with per-source throttles inside the repositories.
 * A failure in one space must never postpone or cancel the other three spaces. This is invoked
 * only from the foreground Activity; it is NOT a multi-market background trading service.
 */
class WorkspaceOverviewCoordinator(
    private val scope: CoroutineScope,
    private val sources: Map<OverviewCheck, suspend () -> Unit>,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutex = Mutex()
    private var lastAttempt = 0L

    fun refreshNow() { scope.launch { refresh(System.currentTimeMillis()) } }

    internal suspend fun refresh(now: Long): Unit = mutex.withLock {
        val tick = elapsed()
        if (lastAttempt != 0L && tick - lastAttempt in 0L until 60_000L) return@withLock
        lastAttempt = tick
        supervisorScope {
            WorkspaceOverviewSchedule.checks(now).mapNotNull { sources[it] }.map { check ->
                async {
                    try { check() } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* repositories report source-specific errors in their own StateFlows */ }
                }
            }.awaitAll()
        }
        Unit
    }
}
