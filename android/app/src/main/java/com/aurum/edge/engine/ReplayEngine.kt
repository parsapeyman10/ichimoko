package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalProfile

/**
 * Immutable bar-replay state shared by the learning UI and the production strategy engine.
 *
 * A replay cursor is a hard information boundary: [visibleBars] contains no bar after it, and
 * both the signal and the optional report are recomputed from that prefix. The UI can therefore
 * seek backwards or play forwards without ever asking the engine about a future candle.
 */
object ReplayEngine {
    data class Config(
        val initialBalance: Double,
        val riskPercent: Double,
        val spreadPrice: Double,
        val commissionPerOz: Double,
        val threshold: Double,
        val signalProfile: SignalProfile,
    )

    data class Session(
        val allBars: List<Candle>,
        val interval: Interval,
        val symbol: String,
        val dataSource: String,
        val config: Config,
        val startCursor: Int,
        val cursor: Int,
        val speed: Float = 1.0f,
        val playing: Boolean = false,
        val providerFetchedAt: Long = 0L,
        val observedGapCount: Int = 0,
    ) {
        val lastCursor: Int get() = allBars.lastIndex
        val visibleBars: List<Candle> get() = allBars.take((cursor + 1).coerceAtLeast(0))
        val currentBar: Candle? get() = allBars.getOrNull(cursor)
    }

    data class Snapshot(
        val session: Session,
        val visibleBars: List<Candle>,
        val currentBar: Candle?,
        /** Signal at the cursor, never a signal from a later bar. */
        val signal: Signal?,
        /** Batch fills/equity over the visible prefix using the same Backtester and SignalEngine. */
        val report: Backtester.Result?,
    ) {
        val warmedUp: Boolean get() = visibleBars.size >= SignalEngine.minBars(session.interval)
        val isAtEnd: Boolean get() = session.cursor >= session.lastCursor
    }

    fun create(
        candles: List<Candle>,
        interval: Interval,
        symbol: String,
        dataSource: String,
        config: Config,
        startCursor: Int = SignalEngine.minBars(interval),
        providerFetchedAt: Long = 0L,
        observedGapCount: Int = 0,
    ): Session {
        val bars = candles.filter { it.closed }.sortedBy { it.time }
        require(bars.size >= 2) { "برای replay حداقل دو کندل واقعی لازم است" }
        require(bars.zipWithNext().all { (a, b) -> b.time > a.time }) {
            "کندل‌های replay باید مرتب و یکتا باشند"
        }
        val first = startCursor.coerceIn(0, bars.lastIndex)
        return Session(
            allBars = bars,
            interval = interval,
            symbol = symbol,
            dataSource = dataSource,
            config = config,
            startCursor = first,
            cursor = first,
            providerFetchedAt = providerFetchedAt,
            observedGapCount = observedGapCount,
        )
    }

    fun seek(session: Session, cursor: Int): Session =
        session.copy(cursor = cursor.coerceIn(0, session.lastCursor), playing = false)

    fun step(session: Session, amount: Int = 1): Session =
        session.copy(cursor = (session.cursor + amount).coerceIn(0, session.lastCursor))

    fun reset(session: Session): Session = session.copy(cursor = session.startCursor, playing = false)

    fun setSpeed(session: Session, speed: Float): Session =
        session.copy(speed = speed.coerceIn(0.25f, 8.0f))

    /** Compute every visible output from the prefix ending at the current cursor. */
    fun snapshot(session: Session): Snapshot {
        val visible = session.visibleBars
        val series = if (visible.size >= SignalEngine.minBars(session.interval)) {
            SignalEngine.series(visible, session.interval)
        } else null
        val signal = series?.let {
            SignalEngine.decide(
                it,
                it.lastIndex,
                session.config.threshold,
                session.config.spreadPrice,
                narrative = true,
                profile = session.config.signalProfile,
            )
        }
        val report = if (visible.size >= SignalEngine.minBars(session.interval)) {
            Backtester.run(
                candles = visible,
                interval = session.interval,
                symbol = session.symbol,
                dataSource = session.dataSource,
                initialBalance = session.config.initialBalance,
                riskPercent = session.config.riskPercent,
                spreadPrice = session.config.spreadPrice,
                commissionPerOz = session.config.commissionPerOz,
                threshold = session.config.threshold,
                signalProfile = session.config.signalProfile,
            )
        } else null
        return Snapshot(session, visible, session.currentBar, signal, report)
    }
}
