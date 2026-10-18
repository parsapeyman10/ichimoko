package com.aurum.edge.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aurum.edge.AurumApplication
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.TradeQuotePolicy
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.data.SpotFallbackClient
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.notify.AlertSoundPlayer
import com.aurum.edge.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the real Forex feed alive while the app is in the background and alerts only on
 * signals produced by the real engine. It performs no data generation of any kind.
 */
class SignalMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var journalJob: Job? = null
    private var settingsJob: Job? = null
    private var sweepJob: Job? = null
    private var positionJobs: Job? = null
    private val notifiedTrades = mutableSetOf<String>()
    private val notifiedOpenTrades = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationsPermitted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as AurumApplication).container
        if (!notificationsPermitted()) {
            container.settingsStore.update { it.copy(backgroundMonitor = false) }
            stopSelf()
            return START_NOT_STICKY // no hidden user-initiated monitor after notification permission revocation
        }
        if (intent == null && !container.settingsStore.read().backgroundMonitor) {
            stopSelf()
            return START_NOT_STICKY // do not resurrect a monitor the user turned off
        }
        if (_running.value) return START_STICKY // repeat start is not a reconnect
        Notifier.ensureChannels(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifier.MONITOR_NOTIFICATION_ID,
                Notifier.buildMonitorNotification(this, "در حال دریافت دیتای واقعی…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            true
        }.getOrElse { false }
        if (!started) {
            _running.value = false
            container.settingsStore.update { it.copy(backgroundMonitor = false) }
            container.autoPaperTrader.stopped("سرویس پس‌زمینه شروع نشد؛ ورود خودکار فقط هنگام باز بودن برنامه بررسی می‌شود")
            stopSelf()
            return START_NOT_STICKY
        }

        _running.value = true // only after startForeground succeeded, not just a queued start request
        container.settingsStore.update { it.copy(backgroundMonitor = true) }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            container.settingsStore.settings.collect { current ->
                if (!current.backgroundMonitor) {
                    container.autoPaperTrader.stopped("پایش متوقف شد")
                    stopSelf()
                }
            }
        }
        // Periodic 50+ universe continuous sweep across commodities, forex, crypto, stocks.
        // (The old separate news/AI loops are gone; the watchlist cards warm-up folds in here:
        // one watch refresh every third sweep tick, under WatchRepository's own throttle.)
        sweepJob?.cancel()
        sweepJob = scope.launch {
            var sweepTurns = 0
            while (isActive) {
                if (!notificationsPermitted()) { stopSelf(); break }
                if (sweepTurns++ % 3 == 0 &&
                    !MarketHours.weekendClosedFor(container.settingsStore.read().symbol))
                    container.watch.refreshNow() // display/radar cards stay warm under their own throttle
                val config = container.settingsStore.read()
                if (config.backgroundMonitor) {
                    if (!config.notifyOnSignal) {
                        container.pairScanner.refreshNow() // records honest online/error/alert-off statuses
                    } else {
                        try {
                            container.pairScanner.refreshNow { candidate ->
                                if (Notifier.canNotifyVerified(this@SignalMonitorService, config.alertSoundUri)) {
                                    Notifier.notifyVerifiedOpportunity(this@SignalMonitorService, candidate,
                                        config.alertSoundUri)
                                }
                            }
                        } catch (_: Exception) { /* radar statuses carry per-pair errors */ }
                    }
                }
                delay(60_000L)
            }
        }

        stateJob?.cancel()
        stateJob = scope.launch {
            try {
                container.journalStore.load()
            } catch (_: Exception) {
                container.autoPaperTrader.stopped("ژورنال آسیب‌دیده است؛ ورود خودکار کاغذی متوقف شد")
                return@launch
            }
            val alertsAvailable = runCatching { container.opportunityStore.load(); true }.getOrDefault(false)
            container.market.start()
            // Use collect, not collectLatest: never cancel a partially persisted paper entry
            // just because another tick arrives during the atomic journal write.
            container.verifiedMarket.collect { state ->
                if (!container.settingsStore.read().backgroundMonitor) {
                    container.autoPaperTrader.stopped("پایش متوقف شد")
                    stopSelf()
                    return@collect
                }
                // Android requires an ongoing foreground notification. Keep it neutral;
                // feed failures still block entries/settlement, but never trigger an alert.
                val text = "پایش معاملات کاغذی"
                runCatching {
                    NotificationManagerCompat.from(this@SignalMonitorService).notify(
                        Notifier.MONITOR_NOTIFICATION_ID,
                        Notifier.buildMonitorNotification(this@SignalMonitorService, text),
                    )
                }
                val signal = state.signal
                var newCandidate: PaperOpportunity? = null
                if (alertsAvailable && signal?.isActionable == true &&
                    container.settingsStore.read().notifyOnSignal) {
                    val snapshot = runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
                    val config = container.settingsStore.read()
                    if (PaperAlertRules.blocker(state, config,
                            container.journalStore.trades.value, snapshot) == null) {
                        // Re-check after computation: technical/price/MTF state can change between flows.
                        val latest = container.verifiedMarket.value
                        val recentConfig = container.settingsStore.read()
                        if (latest.symbol == state.symbol &&
                            latest.signal?.barTime == signal.barTime &&
                            PaperAlertRules.blocker(latest, recentConfig,
                                container.journalStore.trades.value, snapshot) == null) {
                            val ict = IctEntryRules.approvedEvidence(latest)
                            if (ict != null && snapshot != null &&
                                Notifier.canNotifyVerified(this@SignalMonitorService, recentConfig.alertSoundUri)) {
                                val item = runCatching { PaperOpportunity.from(latest.signal!!, latest.symbol,
                                    latest.lastPrice!!, MtfSnapshotRecord.from(snapshot), ict) }.getOrNull()
                                newCandidate = item
                            }
                        }
                    }
                }
                // A candidate is NOT an entry. Wait for the atomic journal write before notifying.
                val currentSettings = container.settingsStore.read()
                val autoEnabled = currentSettings.autoPaperTrading
                val canAlert = currentSettings.notifyOnSignal &&
                    Notifier.canNotifyVerified(this@SignalMonitorService, currentSettings.alertSoundUri)
                val opened = if (autoEnabled) container.autoPaperTrader.onMarketUpdate(state) else null
                if (opened != null) {
                    if (alertsAvailable) runCatching {
                        newCandidate?.let { container.opportunityStore.record(it) }
                        container.opportunityStore.linkTrade(opened)
                    }
                    if (canAlert) {
                        Notifier.notifyRecordedAutoEntry(this@SignalMonitorService, opened,
                            currentSettings.alertSoundUri)
                    }
                } else if (!autoEnabled) {
                    val candidate = newCandidate
                    val currentSettings = container.settingsStore.read()
                    if (candidate != null && currentSettings.notifyOnSignal &&
                        runCatching { container.opportunityStore.record(candidate) }.getOrDefault(false)) {
                        Notifier.notifyVerifiedOpportunity(this@SignalMonitorService, candidate,
                            currentSettings.alertSoundUri)
                    }
                }
            }
        }

        journalJob?.cancel()
        journalJob = scope.launch {
            try { container.journalStore.load() } catch (_: Exception) { return@launch }
            val canLink = runCatching { container.opportunityStore.load(); true }.getOrDefault(false)
            // A service restart must not re-notify all old, already processed paper trades.
            notifiedTrades.addAll(container.journalStore.trades.value.filterNot { it.isOpen }.map { it.id })
            notifiedOpenTrades.addAll(container.journalStore.trades.value.filter { it.isOpen }.map { it.id })
            container.journalStore.trades.collect { trades ->
                val currentSettings = container.settingsStore.read()
                if (canLink) trades.filter { it.signalBarTime != null }.forEach { trade ->
                    runCatching { container.opportunityStore.linkTrade(trade) }
                }
                // Notify with rich details on EVERY newly opened trade (even if user is in-app)
                trades.filter { it.isOpen }.forEach { trade ->
                    if (notifiedOpenTrades.add(trade.id)) {
                        if (trade.autoOpened) Notifier.notifyRecordedAutoEntry(
                            this@SignalMonitorService, trade, currentSettings.alertSoundUri)
                        else Notifier.notifyTradeOpened(
                            this@SignalMonitorService, trade, currentSettings.alertSoundUri)
                    }
                }
                // Notify on trade closed / settled (TP, SL, manual close)
                trades.filter { !it.isOpen }.forEach { trade ->
                    if (notifiedTrades.add(trade.id)) {
                        Notifier.notifyClosedTrade(this@SignalMonitorService, trade)
                    }
                }
            }
        }

        // Each OPEN symbol has its own supervised, cancellable worker. A slow/failed
        // provider for one pair must not delay the other positions, news or scanner.
        positionJobs?.cancel()
        positionJobs = scope.launch {
            try { container.journalStore.load() } catch (_: Exception) { return@launch }
            val workers = mutableMapOf<String, Pair<Long, Job>>()
            val spot = SpotFallbackClient()
            try {
                container.journalStore.trades.collect { trades ->
                    val open = trades.filter { it.isOpen }.groupBy { it.symbol }
                        .mapValues { (_, rows) -> rows.minOf { it.openedAt } }
                    workers.toMap().forEach { (symbol, worker) ->
                        if (open[symbol] != worker.first) {
                            worker.second.cancel()
                            workers.remove(symbol)
                        }
                    }
                    open.forEach { (symbol, openedAt) ->
                        if (symbol !in workers) {
                            val worker = launch(Dispatchers.IO) {
                                val crypto = com.aurum.edge.data.CryptoCatalog.isCrypto(symbol)
                                // Separate closed-candle lane: never slow the ordered tick stream.
                                if (crypto) launch {
                                    while (isActive) {
                                        try {
                                            container.openTradeTicks.syncCryptoClosedBars(symbol, openedAt)
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            // Keep the prior real stop/valuation when history is unavailable.
                                        }
                                        delay(60_000L)
                                    }
                                }
                                while (isActive) {
                                    try {
                                        if (!MarketHours.closedFor(symbol)) {
                                            if (crypto) {
                                                // Official same-venue trade stream; SDK reconnects and
                                                // attempts recovery if the server advertises it. REST
                                                // bootstrap/fallback remains inside the process-wide gate.
                                                container.openTradeTicks.watch(symbol, openedAt)
                                            } else {
                                                val quote = spot.fetchQuote(symbol)
                                                if (TradeQuotePolicy.accepts(quote))
                                                    container.journalStore.settleTick(symbol, quote.price,
                                                        quote.at, expectedOpenedAt = openedAt)
                                            }
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        // An unavailable quote is not a tick or an exit. Retry
                                        // independently; the crypto monitor records coverage gaps.
                                    }
                                    delay(if (crypto) 3_000L else 1_000L)
                                }
                            }
                            workers[symbol] = openedAt to worker
                        }
                    }
                }
            } finally { workers.values.forEach { it.second.cancel() } }
        }
        return START_STICKY
    }

    // Android 15+ limits dataSync foreground services to 6 hours per 24 hours in background.
    // Never leave a timed-out service running or claim continuous monitoring after it stops.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        val container = (application as AurumApplication).container
        container.settingsStore.update { it.copy(backgroundMonitor = false) }
        // Do not leave a headless polling loop alive after Android times out/stops the FGS.
        // The visible Forex screen restarts the feed on foreground resume if needed.
        if (!ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)) container.market.stop()
        scope.cancel()
        AlertSoundPlayer.stop()
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        const val ACTION_STOP = "com.aurum.edge.STOP_MONITOR"

        fun start(context: android.content.Context): Boolean {
            val intent = Intent(context, SignalMonitorService::class.java)
            return runCatching { context.startForegroundService(intent); true }.getOrDefault(false)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, SignalMonitorService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
