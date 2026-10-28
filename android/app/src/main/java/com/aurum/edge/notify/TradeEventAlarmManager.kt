package com.aurum.edge.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.aurum.edge.MainActivity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

enum class AlarmTonePreset(
    val id: String,
    val titleFa: String,
    val subtitleFa: String,
) {
    CUSTOM_ONLY(
        id = "CUSTOM_ONLY",
        titleFa = "🎵 فقط زنگ انتخابی گوشی (Ding-Dong / Red Dwarf / Latch)",
        subtitleFa = "پخش مستقیم و تمیز زنگ انتخابی بدون هیچ بوق یا صدای اضافی",
    ),
    SOFT_CHIME(
        id = "SOFT_CHIME",
        titleFa = "🔔 زنگ ملایم و آرام (Soft Chime)",
        subtitleFa = "ملودی کوتاه و لطیف بدون ایجاد شوک صوتی",
    ),
    SHARP_RADAR(
        id = "SHARP_RADAR",
        titleFa = "📡 پالس راداری پرقدرت (High-Alert Radar)",
        subtitleFa = "پالس‌های تند و بلند برای محیط‌های شلوغ",
    ),
    INSTITUTIONAL(
        id = "INSTITUTIONAL",
        titleFa = "🎼 ملودی سازمانی استاندارد",
        subtitleFa = "۳ نت صعودی برای ورود، ۴ نت پیروزی برای حد سود، ۲ بوق بم برای حد ضرر",
    ),
    VIBRATE_ONLY(
        id = "VIBRATE_ONLY",
        titleFa = "📳 فقط ویبره و نوتیفیکیشن (بی‌صدا)",
        subtitleFa = "قطع کامل صدای آلارم و اکتفا به الگوی ویبره اختصاصی",
    );

    companion object {
        fun fromId(raw: String?): AlarmTonePreset =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: CUSTOM_ONLY
    }
}

enum class TradeAlarmKind(
    val channelId: String,
    val legacyChannelId: String,
    val channelNameFa: String,
    val badgeFa: String,
    val defaultRingtoneTitle: String,
) {
    ENTRY(
        channelId = "aurum_alarm_entry_silent_v2",
        legacyChannelId = "aurum_alarm_entry_v1",
        channelNameFa = "آلارم ورود به معامله (Entry)",
        badgeFa = "🚀 آلارم ورود به معامله",
        defaultRingtoneTitle = "Ding-Dong",
    ),
    TAKE_PROFIT(
        channelId = "aurum_alarm_tp_silent_v2",
        legacyChannelId = "aurum_alarm_tp_v1",
        channelNameFa = "آلارم رسیدن به حد سود (TP)",
        badgeFa = "🎯 آلارم رسیدن به حد سود (TP)",
        defaultRingtoneTitle = "Red Dwarf",
    ),
    STOP_LOSS(
        channelId = "aurum_alarm_sl_silent_v2",
        legacyChannelId = "aurum_alarm_sl_v1",
        channelNameFa = "آلارم برخورد به حد ضرر (SL)",
        badgeFa = "🛑 آلارم برخورد به حد ضرر (SL)",
        defaultRingtoneTitle = "Latch",
    ),
}

/**
 * مدیریت یکپارچه ۳ آلارم مجزا بدون هیچ صدای اضافی یا تداخل صوتی:
 * ۱. آلارم ورود به معامله (`ENTRY` — پیش‌فرض: `Ding-Dong`)
 * ۲. آلارم رسیدن به حد سود (`TAKE_PROFIT` — پیش‌فرض: `Red Dwarf`)
 * ۳. آلارم برخورد به حد ضرر (`STOP_LOSS` — پیش‌فرض: `Latch`)
 */
object TradeEventAlarmManager {

    private val notificationIdSeq = AtomicInteger(4000)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeRingtone: Ringtone? = null
    private val resolvedTitleUriCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        TradeAlarmKind.entries.forEach { kind ->
            // حذف کانال قدیمی که صدای پیش‌فرض نوتیفیکیشن سیستم را اضافه پخش می‌کرد
            runCatching { nm.deleteNotificationChannel(kind.legacyChannelId) }

            // کانال جدید کاملاً بی‌صدا است تا فقط و فقط صدای دقیق آلارم انتخابی پخش شود (بدون صدای اضافه سیستم)
            val channel = NotificationChannel(
                kind.channelId,
                kind.channelNameFa,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = kind.badgeFa
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * جستجوی خودکار URI زنگ روی گوشی بر اساس نام (`Ding-Dong`, `Red Dwarf`, `Latch`)
     * تا زنگ‌های انتخابی کاربر بدون نیاز به انتخاب مجدد و بدون صدای اضافی پخش شوند.
     */
    fun resolvePhoneRingtoneUriByName(context: Context, targetTitle: String): String {
        val cleanTarget = targetTitle.trim()
        if (cleanTarget.isBlank()) return ""
        resolvedTitleUriCache[cleanTarget.lowercase(Locale.ROOT)]?.let { return it }

        val normTarget = cleanTarget.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "")
        return runCatching {
            val rm = RingtoneManager(context.applicationContext)
            rm.setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            val cursor = rm.cursor ?: return@runCatching ""
            var matchedUri = ""
            cursor.use { c ->
                var idx = 0
                while (c.moveToNext()) {
                    val title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty()
                    val normTitle = title.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "")
                    if (normTitle == normTarget || normTitle.contains(normTarget) || normTarget.contains(normTitle)) {
                        val uri = rm.getRingtoneUri(idx)
                        if (uri != null) {
                            matchedUri = uri.toString()
                            break
                        }
                    }
                    idx++
                }
            }
            if (matchedUri.isNotBlank()) {
                resolvedTitleUriCache[cleanTarget.lowercase(Locale.ROOT)] = matchedUri
            }
            matchedUri
        }.getOrDefault("")
    }

    fun triggerEntryAlarm(
        context: Context,
        trade: PaperTrade,
        enabled: Boolean = true,
        customSoundUri: String = "",
    ) {
        if (!enabled) return
        val sideFa = if (trade.action == SignalAction.BUY) "خرید LONG" else "فروش SHORT"
        val title = "🚀 ورود به معامله: ${trade.symbol} ($sideFa · ${trade.interval.label})"
        val body = String.format(
            Locale.US,
            "ورود: %s · حد ضرر: %s · حد سود: %s · R:R = 1:%.2f · %s",
            fmtPrice(trade.entry),
            fmtPrice(trade.stopLoss),
            fmtPrice(trade.takeProfit),
            trade.riskReward,
            trade.strategyTitleFa,
        )
        fireAlarm(context, TradeAlarmKind.ENTRY, title, body, customSoundUri)
    }

    fun triggerTakeProfitAlarm(
        context: Context,
        trade: PaperTrade,
        enabled: Boolean = true,
        customSoundUri: String = "",
    ) {
        if (!enabled) return
        val pnl = trade.pnlUsd ?: ((trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0) * trade.riskReward)
        val rMult = trade.rMultiple ?: trade.riskReward
        val title = String.format(
            Locale.US,
            "🎯 برخورد به حد سود (TP): %s (+$%.2f · +%.2fR)",
            trade.symbol,
            abs(pnl),
            abs(rMult),
        )
        val body = String.format(
            Locale.US,
            "معامله %s [%s] در قیمت %s با سود +$%.2f بسته شد · %s",
            trade.symbol,
            trade.interval.label,
            fmtPrice(trade.exitPrice ?: trade.takeProfit),
            abs(pnl),
            trade.exitReason ?: "رسیدن به حد سود (TP)",
        )
        fireAlarm(context, TradeAlarmKind.TAKE_PROFIT, title, body, customSoundUri)
    }

    fun triggerStopLossAlarm(
        context: Context,
        trade: PaperTrade,
        enabled: Boolean = true,
        customSoundUri: String = "",
    ) {
        if (!enabled) return
        val pnl = trade.pnlUsd ?: -(trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0)
        val title = String.format(
            Locale.US,
            "🛑 برخورد به حد ضرر (SL): %s (-$%.2f)",
            trade.symbol,
            abs(pnl),
        )
        val body = String.format(
            Locale.US,
            "معامله %s [%s] در قیمت %s بسته شد · %s",
            trade.symbol,
            trade.interval.label,
            fmtPrice(trade.exitPrice ?: trade.stopLoss),
            trade.exitReason ?: "برخورد به حد ضرر (SL)",
        )
        fireAlarm(context, TradeAlarmKind.STOP_LOSS, title, body, customSoundUri)
    }

    fun triggerTestAlarm(
        context: Context,
        kind: TradeAlarmKind,
        customSoundUri: String = "",
    ) {
        val (title, body) = when (kind) {
            TradeAlarmKind.ENTRY ->
                "🚀 [تست آلارم ورود — Ding-Dong] XAU/USD (خرید LONG · 15m)" to
                    "تست صدای ورود به معامله · ورود: 2745.00 · SL: 2738.00 · TP: 2762.50 (R:R 1:2.50)"
            TradeAlarmKind.TAKE_PROFIT ->
                "🎯 [تست آلارم حد سود TP — Red Dwarf] BTC/USDT (+$12.50 · +2.50R)" to
                    "تست صدای رسیدن به حد سود (TP) · سود قطعی +$12.50 به موجودی اضافه شد."
            TradeAlarmKind.STOP_LOSS ->
                "🛑 [تست آلارم حد ضرر SL — Latch] EUR/USD (-$5.00)" to
                    "تست صدای برخورد به حد ضرر (SL) · ریسک کنترل‌شده -$5.00."
        }
        fireAlarm(context, kind, title, body, customSoundUri)
    }

    private fun fireAlarm(
        context: Context,
        kind: TradeAlarmKind,
        title: String,
        body: String,
        customSoundUri: String,
    ) {
        runCatching {
            val prefs = context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)
            val volumePct = prefs.getInt("alarm_volume_pct_v3913", 90).coerceIn(0, 100)
            val preset = AlarmTonePreset.fromId(prefs.getString("alarm_tone_preset_v3913", "CUSTOM_ONLY"))
            val vibrateEnabled = prefs.getBoolean("alarm_vibration_enabled_v3913", true)

            val savedUri = when (kind) {
                TradeAlarmKind.ENTRY -> prefs.getString("entry_alarm_sound_uri_v3913", "").orEmpty()
                TradeAlarmKind.TAKE_PROFIT -> prefs.getString("tp_alarm_sound_uri_v3913", "").orEmpty()
                TradeAlarmKind.STOP_LOSS -> prefs.getString("sl_alarm_sound_uri_v3913", "").orEmpty()
            }.ifBlank { customSoundUri }

            val savedName = when (kind) {
                TradeAlarmKind.ENTRY -> prefs.getString("entry_alarm_sound_name_v3913", kind.defaultRingtoneTitle).orEmpty().ifBlank { kind.defaultRingtoneTitle }
                TradeAlarmKind.TAKE_PROFIT -> prefs.getString("tp_alarm_sound_name_v3913", kind.defaultRingtoneTitle).orEmpty().ifBlank { kind.defaultRingtoneTitle }
                TradeAlarmKind.STOP_LOSS -> prefs.getString("sl_alarm_sound_name_v3913", kind.defaultRingtoneTitle).orEmpty().ifBlank { kind.defaultRingtoneTitle }
            }

            val effectiveUri = savedUri.ifBlank {
                resolvePhoneRingtoneUriByName(context, savedName).also { found ->
                    if (found.isNotBlank()) {
                        val keyUri = when (kind) {
                            TradeAlarmKind.ENTRY -> "entry_alarm_sound_uri_v3913"
                            TradeAlarmKind.TAKE_PROFIT -> "tp_alarm_sound_uri_v3913"
                            TradeAlarmKind.STOP_LOSS -> "sl_alarm_sound_uri_v3913"
                        }
                        prefs.edit().putString(keyUri, found).apply()
                    }
                }
            }.ifBlank {
                prefs.getString("verified_alert_sound_uri", "").orEmpty()
            }

            ensureChannels(context)
            if (preset != AlarmTonePreset.VIBRATE_ONLY && volumePct > 0) {
                playSingleCleanAlarmSound(context, kind, effectiveUri, volumePct, preset)
            }
            if (vibrateEnabled) {
                vibratePattern(context, kind)
            }
            postHeadsUpNotification(context, kind, title, body)
        }
    }

    /**
     * پخش تمیز و تک‌صدایی آلارم:
     * - اگر زنگ گوشی (`Ding-Dong`, `Red Dwarf`, `Latch` یا هر زنگ انتخابی) موجود است یا حالت `CUSTOM_ONLY` فعال است،
     *   فقط همان زنگ گوشی پخش می‌شود و هیچ بوق اضافه‌ای از `ToneGenerator` پخش نمی‌گردد.
     * - قبل از پخش صدای جدید، هر صدای در حال پخش قبلی متوقف می‌شود تا هم‌پوشانی صوتی رخ ندهد.
     */
    private fun playSingleCleanAlarmSound(
        context: Context,
        kind: TradeAlarmKind,
        customSoundUri: String,
        volumePercent: Int = 90,
        preset: AlarmTonePreset = AlarmTonePreset.CUSTOM_ONLY,
    ) {
        val safeVol = volumePercent.coerceIn(5, 100)
        runCatching { activeRingtone?.stop() }

        val shouldPlayRingtoneOnly = preset == AlarmTonePreset.CUSTOM_ONLY || customSoundUri.isNotBlank()
        if (shouldPlayRingtoneOnly) {
            var playedRingtone = false
            runCatching {
                val uri = if (customSoundUri.isNotBlank()) {
                    Uri.parse(customSoundUri)
                } else {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                if (uri != null) {
                    val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
                    if (ringtone != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = false
                            ringtone.volume = (safeVol / 100f).coerceIn(0.05f, 1.0f)
                        }
                        activeRingtone = ringtone
                        ringtone.play()
                        playedRingtone = true
                    }
                }
            }
            if (playedRingtone) return
        }

        // فقط در صورتی که کاربر صراحتاً پروفایل سینت‌سایزر را بدون زنگ انتخابی خواسته باشد:
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, safeVol)
            when (preset) {
                AlarmTonePreset.SOFT_CHIME -> {
                    when (kind) {
                        TradeAlarmKind.ENTRY -> {
                            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 220L)
                        }
                        TradeAlarmKind.TAKE_PROFIT -> {
                            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 130)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 280L)
                        }
                        TradeAlarmKind.STOP_LOSS -> {
                            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 170)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 320L)
                        }
                    }
                }

                AlarmTonePreset.SHARP_RADAR -> {
                    when (kind) {
                        TradeAlarmKind.ENTRY -> {
                            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 350L)
                        }
                        TradeAlarmKind.TAKE_PROFIT -> {
                            toneGen.startTone(ToneGenerator.TONE_SUP_CONFIRM, 220)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 380L)
                        }
                        TradeAlarmKind.STOP_LOSS -> {
                            toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 250)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 400L)
                        }
                    }
                }

                else -> {
                    when (kind) {
                        TradeAlarmKind.ENTRY -> {
                            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 160)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 300L)
                        }
                        TradeAlarmKind.TAKE_PROFIT -> {
                            toneGen.startTone(ToneGenerator.TONE_SUP_CONFIRM, 200)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 350L)
                        }
                        TradeAlarmKind.STOP_LOSS -> {
                            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 220)
                            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 380L)
                        }
                    }
                }
            }
        }
    }

    private fun vibratePattern(context: Context, kind: TradeAlarmKind) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val timings = when (kind) {
                TradeAlarmKind.ENTRY -> longArrayOf(0L, 120L, 70L, 200L)
                TradeAlarmKind.TAKE_PROFIT -> longArrayOf(0L, 160L, 80L, 160L, 80L, 300L)
                TradeAlarmKind.STOP_LOSS -> longArrayOf(0L, 320L, 120L, 380L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
    }

    private fun postHeadsUpNotification(
        context: Context,
        kind: TradeAlarmKind,
        title: String,
        body: String,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, kind.ordinal + 100, launchIntent, pendingFlags)

        val notification = NotificationCompat.Builder(context, kind.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)
            .setSound(null)
            .setVibrate(null)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notificationIdSeq.incrementAndGet(), notification)
    }

    private fun fmtPrice(v: Double): String {
        val a = abs(v)
        val fmt = when {
            a >= 500.0 -> "%.2f"
            a >= 10.0 -> "%.3f"
            else -> "%.5f"
        }
        return String.format(Locale.US, fmt, v)
    }
}
