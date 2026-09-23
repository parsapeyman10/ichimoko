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
import com.aurum.edge.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the real feed alive while the app is in the background and alerts only on
 * signals produced by the real engine. It performs no data generation of any kind.
 */
class SignalMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var journalJob: Job? = null
    private var lastAlertKey: String? = null
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
            stopSelf()
            return START_NOT_STICKY
        }

        container.settingsStore.update { it.copy(backgroundMonitor = true) }
        container.market.start()

        stateJob?.cancel()
        stateJob = scope.launch {
            container.market.state.collectLatest { state ->
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
                if (signal != null && signal.isActionable && !state.showingCachedData &&
                    state.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
                    state.candles.lastOrNull()?.time?.let { System.currentTimeMillis() - it <= state.interval.millis * 2 } == true &&
                    container.settingsStore.read().notifyOnSignal) {
                    val key = "${signal.action}-${signal.interval.label}-${signal.barTime}"
                    if (key != lastAlertKey) {
                        lastAlertKey = key
                        Notifier.notifySignal(this@SignalMonitorService, signal)
                    }
                }
            }
        }

        journalJob?.cancel()
        journalJob = scope.launch {
            container.journalStore.load()
            container.journalStore.trades.collectLatest { trades ->
                trades.filter { !it.isOpen }.forEach { trade ->
                    if (notifiedTrades.add(trade.id)) {
                        Notifier.notifyClosedTrade(this@SignalMonitorService, trade)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val container = (application as AurumApplication).container
        container.settingsStore.update { it.copy(backgroundMonitor = false) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.aurum.edge.STOP_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, SignalMonitorService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalMonitorService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
