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
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.TradeQuotePolicy
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.notify.AlertSoundPlayer
import com.aurum.edge.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        // Continuous universe sweep is now owned by AppContainer.sweepCoordinator.
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
