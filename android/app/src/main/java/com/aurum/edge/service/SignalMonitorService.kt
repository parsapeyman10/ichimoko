package com.aurum.edge.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat
import com.aurum.edge.AurumApplication
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.notify.AlertSoundPlayer
import com.aurum.edge.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the real feed alive while the app is in the background and alerts only on
 * signals produced by the real engine. It performs no data generation of any kind.
 */
class SignalMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var journalJob: Job? = null
    private var newsJob: Job? = null
    private val notifiedTrades = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as AurumApplication).container
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
            container.settingsStore.update { it.copy(backgroundMonitor = false, autoPaperTrading = false) }
            container.autoPaperTrader.stopped("سرویس پس‌زمینه شروع نشد؛ ورود خودکار خاموش شد")
            stopSelf()
            return START_NOT_STICKY
        }

        container.settingsStore.update { it.copy(backgroundMonitor = true) }
        newsJob?.cancel()
        newsJob = scope.launch {
            while (isActive) {
                if (container.settingsStore.read().let { it.autoPaperTrading || it.pauseOnNews || it.notifyOnSignal } &&
                    container.settingsStore.read().newsBaseUrl.isNotBlank()) container.news.refreshNow()
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
                val text = when (state.feed.mode) {
                    FeedMode.LIVE -> "زنده · ${state.lastPrice?.let { String.format("%.2f", it) } ?: "—"}"
                    FeedMode.POLLING -> "به‌روزرسانی دوره‌ای · ${state.lastPrice?.let { String.format("%.2f", it) } ?: "—"}"
                    FeedMode.CONNECTING -> "در حال اتصال…"
                    FeedMode.OFFLINE -> "آفلاین — آخرین دیتای واقعی: ${state.candles.lastOrNull()?.time ?: "—"}"
                    FeedMode.NO_KEY -> "کلید API لازم است"
                }
                runCatching {
                    NotificationManagerCompat.from(this@SignalMonitorService).notify(
                        Notifier.MONITOR_NOTIFICATION_ID,
                        Notifier.buildMonitorNotification(this@SignalMonitorService, text),
                    )
                }
                val signal = state.signal
                if (alertsAvailable && signal?.isActionable == true &&
                    container.settingsStore.read().notifyOnSignal) {
                    val snapshot = runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
                    val config = container.settingsStore.read()
                    val headlines = container.news.state.value
                    if (PaperAlertRules.blocker(state, config, headlines,
                            container.journalStore.trades.value, snapshot) == null) {
                        // Re-check after computation: a veto/news/price can change between flows.
                        val latest = container.verifiedMarket.value
                        val recentNews = container.news.state.value
                        val recentConfig = container.settingsStore.read()
                        if (latest.symbol == state.symbol && latest.signal?.barTime == signal.barTime &&
                            PaperAlertRules.blocker(latest, recentConfig, recentNews,
                                container.journalStore.trades.value, snapshot) == null) {
                            val evidence = NewsConfluence.record(recentNews)
                            if (evidence != null && snapshot != null &&
                                Notifier.canNotifyVerified(this@SignalMonitorService, recentConfig.alertSoundUri)) {
                                val item = PaperOpportunity.from(latest.signal!!, latest.symbol,
                                    latest.lastPrice!!, MtfSnapshotRecord.from(snapshot), evidence)
                                if (runCatching { container.opportunityStore.record(item) }.getOrDefault(false)) {
                                    Notifier.notifyVerifiedOpportunity(this@SignalMonitorService, item,
                                        recentConfig.alertSoundUri)
                                }
                            }
                        }
                    }
                }
                if (container.settingsStore.read().autoPaperTrading) {
                    container.autoPaperTrader.onMarketUpdate(state)
                    if (alertsAvailable && signal != null) {
                        container.journalStore.trades.value.firstOrNull {
                            it.symbol == state.symbol && it.interval == state.interval &&
                                it.signalBarTime == signal.barTime && it.action == signal.action
                        }?.let { runCatching { container.opportunityStore.linkTrade(it) } }
                    }
                }
            }
        }

        journalJob?.cancel()
        journalJob = scope.launch {
            try { container.journalStore.load() } catch (_: Exception) { return@launch }
            val canLink = runCatching { container.opportunityStore.load(); true }.getOrDefault(false)
            // A service restart must not re-notify all old, already closed paper trades.
            notifiedTrades.addAll(container.journalStore.trades.value.filterNot { it.isOpen }.map { it.id })
            container.journalStore.trades.collect { trades ->
                if (canLink) trades.filter { it.signalBarTime != null }.forEach { trade ->
                    runCatching { container.opportunityStore.linkTrade(trade) }
                }
                trades.filter { !it.isOpen }.forEach { trade ->
                    if (notifiedTrades.add(trade.id)) {
                        Notifier.notifyClosedTrade(this@SignalMonitorService, trade)
                    }
                }
            }
        }
        return START_STICKY
    }

    // Android 15+ limits dataSync foreground services to 6 hours per 24 hours in background.
    // Never leave a timed-out service running or claim continuous monitoring after it stops.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        val container = (application as AurumApplication).container
        container.settingsStore.update { it.copy(backgroundMonitor = false, autoPaperTrading = false) }
        scope.cancel()
        AlertSoundPlayer.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.aurum.edge.STOP_MONITOR"

        fun start(context: Context): Boolean {
            val intent = Intent(context, SignalMonitorService::class.java)
            return runCatching { context.startForegroundService(intent); true }.getOrDefault(false)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalMonitorService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
