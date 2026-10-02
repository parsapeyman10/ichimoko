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
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.data.ResearchAlert
import com.aurum.edge.data.ResearchAlerts
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Keeps the real Forex feed alive while the app is in the background and alerts only on
 * signals produced by the real engine. It performs no data generation of any kind.
 */
class SignalMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var journalJob: Job? = null
    private var newsJob: Job? = null
    private var researchAlertJob: Job? = null
    private var settingsJob: Job? = null
    private var sweepJob: Job? = null
    private val notifiedResearch = mutableSetOf<String>()
    private val notifiedTrades = mutableSetOf<String>()

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
        researchAlertJob?.cancel()
        researchAlertJob = scope.launch {
            container.forexCalendar.state.collect { state ->
                if (container.settingsStore.read().backgroundMonitor && !MarketHours.forexWeekendClosed())
                    ResearchAlerts.forex(state, System.currentTimeMillis()).forEach(::postResearchAlert)
            }
        }
        newsJob?.cancel()
        newsJob = scope.launch {
            var turns = 0
            while (isActive) {
                if (!notificationsPermitted()) { stopSelf(); break }
                if (!MarketHours.forexWeekendClosed()) {
                    container.forexCalendar.refreshNow() // weekly export, 1m near release only
                    if (turns % 3 == 0) container.watch.refreshNow() // display/radar cards stay warm under their own throttle
                    if (turns++ % 15 == 0) container.publicWebNews.refreshNow()
                    // The ninth condition expires after ~3 minutes: refresh in server OR client
                    // mode (user's own on-device key) whenever alerts/auto-paper depend on it.
                    if (container.settingsStore.read().let {
                            it.autoPaperTrading || it.pauseOnNews || it.notifyOnSignal } &&
                        container.settingsStore.read().let {
                            it.newsBaseUrl.isNotBlank() || it.hasClientNewsAi }) container.news.refreshNow()
                    // The AI companion refreshes on its own 10-minute throttle; a bias flip is
                    // surfaced once through the informational research channel — never a trade.
                    container.traderAdvisor.refreshNow()
                    container.traderAdvisor.consumeBiasFlip()?.let { flip ->
                        if (notificationsPermitted()) {
                            val from = container.traderAdvisor.state.value.previousBias ?: "—"
                            Notifier.notifyResearch(this@SignalMonitorService,
                                "trader-ai|" + flip.symbol + "|" + flip.generatedAt,
                                "همراه تریدر AI · تغییر جهت ${flip.symbol}",
                                "جهت از $from به ${flip.bias} تغییر کرد — ${flip.summary}")
                        }
                    }
                }
                delay(60_000L)
            }
        }

        // Periodic all-pairs online candle sweep: same nine conditions per pair, educational candidates
        // only — automatic paper fills stay live-tick-only on the selected symbol.
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (isActive) {
                if (!notificationsPermitted()) { stopSelf(); break }
                val config = container.settingsStore.read()
                if (config.backgroundMonitor && !MarketHours.forexWeekendClosed()) {
                    if (!config.notifyOnSignal) {
                        container.pairScanner.refreshNow() // records honest online/error/alert-off statuses
                    } else {
                        try {
                            container.pairScanner.sweepOnce { candidate ->
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
                val text = when (state.feed.mode) {
                    FeedMode.LIVE -> "زنده · ${state.lastPrice?.let { String.format("%.2f", it) } ?: "—"}"
                    FeedMode.POLLING -> "آخرین کندل/تاریخچه آنلاین (نه تیک زنده) · ${state.lastPrice?.let { String.format("%.2f", it) } ?: "—"}"
                    FeedMode.MARKET_CLOSED -> "بازار طبق برنامهٔ معمول بسته است؛ قیمت/ورود بررسی نمی‌شود"
                    FeedMode.DELAYED -> "دادهٔ دیررس؛ اتصال در حال بررسی (معامله مسدود)"
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
                var newCandidate: PaperOpportunity? = null
                if (alertsAvailable && signal?.isActionable == true &&
                    container.settingsStore.read().notifyOnSignal) {
                    val snapshot = runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
                    val config = container.settingsStore.read()
                    val headlines = container.news.state.value
                    if (PaperAlertRules.blocker(state, config, headlines,
                            container.journalStore.trades.value, snapshot) == null) {
                        // Re-check after computation: technical/price/MTF state can change between flows.
                        val latest = container.verifiedMarket.value
                        val recentNews = container.news.state.value
                        val recentConfig = container.settingsStore.read()
                        if (latest.symbol == state.symbol &&
                            latest.signal?.barTime == signal.barTime &&
                            PaperAlertRules.blocker(latest, recentConfig, recentNews,
                                container.journalStore.trades.value, snapshot) == null) {
                            val evidence = NewsConfluence.record(recentNews, latest.symbol)
                            val ict = IctEntryRules.approvedEvidence(latest)
                            if (ict != null && snapshot != null &&
                                Notifier.canNotifyVerified(this@SignalMonitorService, recentConfig.alertSoundUri)) {
                                val item = runCatching { PaperOpportunity.from(latest.signal!!, latest.symbol,
                                    latest.lastPrice!!, MtfSnapshotRecord.from(snapshot), evidence, ict) }.getOrNull()
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
                // A silent automatic entry is worse than no entry. Permissions/channel can
                // be revoked while the service is running; stop BEFORE touching the journal.
                val opened = if (autoEnabled && canAlert) container.autoPaperTrader.onMarketUpdate(state) else null
                if (autoEnabled && !canAlert) container.autoPaperTrader.stopped(
                    "اعلان گوشی مجاز/فعال نیست؛ ورود خودکار کاغذی متوقف است")
                if (opened != null) {
                    if (alertsAvailable) runCatching {
                        newCandidate?.let { container.opportunityStore.record(it) }
                        container.opportunityStore.linkTrade(opened)
                    }
                    val reviewed = runCatching {
                        val review = container.traderAdvisor.reviewPaperEntry(opened, state)
                        container.journalStore.attachAiReview(opened.id, review)
                    }.getOrNull() ?: opened
                    val currentSettings = container.settingsStore.read()
                    if (!Notifier.notifyRecordedAutoEntry(this@SignalMonitorService, reviewed,
                            currentSettings.alertSoundUri)) {
                        container.autoPaperTrader.stopped(
                            "معاملهٔ کاغذی در ژورنال ثبت شد، ولی اعلان توسط سیستم ارسال نشد؛ مجوز/کانال را بررسی کنید")
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

    /** De-duplicate informational news across process/service restarts. No article or key is
     * stored in prefs, only a short digest and timestamp; a notification is NOT a trade.
     */
    @Synchronized
    private fun postResearchAlert(alert: ResearchAlert) {
        val key = MessageDigest.getInstance("SHA-256").digest(alert.evidenceId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        val prefs = getSharedPreferences("observed_news_alerts", MODE_PRIVATE)
        if (prefs.contains(key) || key in notifiedResearch) return
        if (Notifier.notifyResearch(this, alert.evidenceId, alert.title, alert.text)) {
            notifiedResearch.add(key)
            val now = System.currentTimeMillis()
            val update = prefs.edit().putLong(key, now)
            if (prefs.all.size > 500) prefs.all.forEach { (oldKey, time) ->
                if (time is Long && now - time > 7 * 86_400_000L) update.remove(oldKey)
            }
            update.commit() // notifies at most once when device storage permits
        }
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
