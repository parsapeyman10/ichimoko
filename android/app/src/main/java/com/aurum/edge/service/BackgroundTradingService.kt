package com.aurum.edge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aurum.edge.AurumApplication
import com.aurum.edge.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * سرویس بک‌گراند۲۴/۷ (Foreground Service) برای پایش پیوسته ۱۵ نماد در تایم‌فریم ۱۵ دقیقه،
 * اجرای خودکار استراتژی فعال و مدیریت لحظه‌ای حد سود/ضرر (TP/SL) و Preserve Mode حتی در زمان بسته بودن صفحه اپ.
 */
class BackgroundTradingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        acquirePartialWakeLock()
        startForegroundSafe(
            title = "پایش خودکار ۱۵ نماد فعال است",
            text = "اسکن تایم‌فریم ۱۵ دقیقه و رصد معاملات باز در بک‌گراند",
        )
        startBackgroundHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "پایش خودکار ۱۵ نماد فعال است"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "اسکن تایم‌فریم ۱۵ دقیقه و رصد معاملات باز در بک‌گراند"
        startForegroundSafe(title, text)
        startBackgroundHeartbeat()
        return START_STICKY
    }

    private fun startBackgroundHeartbeat() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            val app = application as? AurumApplication ?: return@launch
            while (isActive) {
                runCatching {
                    val settings = app.container.settingsStore.read()
                    val openCount = app.container.journalStore.trades.value.count { it.isOpen }
                    val autoOn = settings.autoPaperTrading
                    val stratName = settings.activeBitcoinStrategyTitleFa()
                    val statusText = buildString {
                        append(if (autoOn) "معامله خودکار: روشن" else "پایش: روشن")
                        append(" · پوزیشن باز: ").append(openCount)
                        append(" · ").append(stratName)
                    }
                    updateNotification("Aurum Edge · رادار ۱۵ نماد (15m)", statusText)
                    renewWakeLockIfNeeded()
                }
                delay(30_000L)
            }
        }
    }

    private fun acquirePartialWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AurumEdge::BgTradingLock").apply {
                setReferenceCounted(false)
                acquire(20 * 60 * 1000L)
            }
        }
    }

    private fun renewWakeLockIfNeeded() {
        runCatching {
            val wl = wakeLock
            if (wl != null && !wl.isHeld) {
                wl.acquire(20 * 60 * 1000L)
            }
        }
    }

    private fun startForegroundSafe(title: String, text: String) {
        val notification = runCatching { buildOngoingNotification(this, title, text) }.getOrNull() ?: return
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID_ONGOING, notification)
            }
            true
        }.getOrElse {
            runCatching {
                startForeground(NOTIFICATION_ID_ONGOING, notification)
                true
            }.getOrDefault(false)
        }
        if (!started) {
            runCatching { stopSelf() }
        }
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification(this, title, text))
        }
    }

    override fun onDestroy() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ONGOING = "aurum_bg_monitor_silent_v3"
        private const val CHANNEL_ALERTS = "aurum_trade_alert_silent_v3"
        private const val NOTIFICATION_ID_ONGOING = 7101
        private const val ACTION_START = "com.aurum.edge.action.START_BG"
        private const val ACTION_STOP = "com.aurum.edge.action.STOP_BG"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            runCatching {
                nm.deleteNotificationChannel("aurum_bg_monitor_channel")
                nm.deleteNotificationChannel("aurum_trade_alert_channel")
            }
            val ongoing = NotificationChannel(
                CHANNEL_ONGOING,
                "پایش بک‌گراند و معامله خودکار",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "فعال نگه‌داشتن رادار ۱۵ نماد و رصد معاملات باز در پس‌زمینه"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                "رویدادهای ورود و خروج معامله (بی‌صدا)",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "اعلان تصویری بدون صدای سیستم گوشی (فقط صدای آلارم اپ پخش می‌شود)"
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ongoing)
            nm.createNotificationChannel(alerts)
        }

        fun syncServiceState(
            context: Context,
            enabled: Boolean,
            title: String = "Aurum Edge · پایش بک‌گراند",
            text: String = "رادار ۱۵ نماد (15m) و رصد معاملات باز فعال است",
        ) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, BackgroundTradingService::class.java).apply {
                    action = if (enabled) ACTION_START else ACTION_STOP
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (enabled) {
                    runCatching {
                        appCtx.startService(intent)
                    }.onFailure {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching { appCtx.startForegroundService(intent) }
                        }
                    }
                } else {
                    runCatching { appCtx.stopService(intent) }
                }
            }
        }

        fun postTradeEventNotification(
            context: Context,
            title: String,
            body: String,
        ) {
            runCatching {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val contentIntent = PendingIntent.getActivity(context, 0, launchIntent, piFlags)
                val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setSound(null)
                    .setVibrate(null)
                    .setDefaults(0)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
                val notifId = 7200 + (System.currentTimeMillis() % 1000).toInt()
                nm.notify(notifId, notif)
            }
        }

        private fun buildOngoingNotification(
            context: Context,
            title: String,
            text: String,
        ): Notification {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val contentIntent = PendingIntent.getActivity(context, 0, launchIntent, piFlags)
            return NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setSound(null)
                .setVibrate(null)
                .setDefaults(0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}
