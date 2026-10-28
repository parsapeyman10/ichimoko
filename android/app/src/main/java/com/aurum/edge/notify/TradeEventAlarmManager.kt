package com.aurum.edge.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
    INSTITUTIONAL(
        id = "INSTITUTIONAL",
        titleFa = "🎼 ملودی سازمانی استاندارد (پیش‌فرض)",
        subtitleFa = "۳ نت صعودی برای ورود، ۴ نت پیروزی برای حد سود، ۲ بوق بم برای حد ضرر",
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
    CUSTOM_ONLY(
        id = "CUSTOM_ONLY",
        titleFa = "🎵 فقط زنگ یا فایل صوتی انتخابی گوشی",
        subtitleFa = "پخش مستقیم زنگ انتخابی بدون بوق سینت‌سایزر",
    ),
    VIBRATE_ONLY(
        id = "VIBRATE_ONLY",
        titleFa = "📳 فقط ویبره و نوتیفیکیشن (بی‌صدا)",
        subtitleFa = "قطع کامل صدای آلارم و اکتفا به الگوی ویبره اختصاصی",
    );

    companion object {
        fun fromId(raw: String?): AlarmTonePreset =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: INSTITUTIONAL
    }
}

enum class TradeAlarmKind(
    val channelId: String,
    val channelNameFa: String,
    val badgeFa: String,
) {
    ENTRY(
        channelId = "aurum_alarm_entry_v1",
        channelNameFa = "آلارم ورود به معامله (Entry)",
        badgeFa = "🚀 آلارم ورود به معامله",
    ),
    TAKE_PROFIT(
        channelId = "aurum_alarm_tp_v1",
        channelNameFa = "آلارم رسیدن به حد سود (TP)",
        badgeFa = "🎯 آلارم رسیدن به حد سود (TP)",
    ),
    STOP_LOSS(
        channelId = "aurum_alarm_sl_v1",
        channelNameFa = "آلارم برخورد به حد ضرر (SL)",
        badgeFa = "🛑 آلارم برخورد به حد ضرر (SL)",
    ),
}

/**
 * مدیریت یکپارچه ۳ آلارم مجزا (صوتی + ویبره + نوتیفیکیشن فوری):
 * ۱. آلارم ورود به معامله (`ENTRY`)
 * ۲. آلارم رسیدن به حد سود (`TAKE_PROFIT`)
 * ۳. آلارم برخورد به حد ضرر (`STOP_LOSS`)
 */
object TradeEventAlarmManager {

    private val notificationIdSeq = AtomicInteger(4000)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        TradeAlarmKind.entries.forEach { kind ->
            val channel = NotificationChannel(
                kind.channelId,
                kind.channelNameFa,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = kind.badgeFa
                enableVibration(true)
                setSound(soundUri, audioAttrs)
            }
            nm.createNotificationChannel(channel)
        }
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
                "🚀 [تست آلارم ورود] XAU/USD (خرید LONG · 15m)" to
                    "تست صدای ورود به معامله · ورود: 2745.00 · SL: 2738.00 · TP: 2762.50 (R:R 1:2.50)"
            TradeAlarmKind.TAKE_PROFIT ->
                "🎯 [تست آلارم حد سود TP] BTC/USDT (+$12.50 · +2.50R)" to
                    "تست صدای رسیدن به حد سود (TP) · سود قطعی +$12.50 به موجودی اضافه شد."
            TradeAlarmKind.STOP_LOSS ->
                "🛑 [تست آلارم حد ضرر SL] EUR/USD (-$5.00)" to
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
            val preset = AlarmTonePreset.fromId(prefs.getString("alarm_tone_preset_v3913", "INSTITUTIONAL"))
            val vibrateEnabled = prefs.getBoolean("alarm_vibration_enabled_v3913", true)
            val perKindUri = when (kind) {
                TradeAlarmKind.ENTRY -> prefs.getString("entry_alarm_sound_uri_v3913", "").orEmpty()
                TradeAlarmKind.TAKE_PROFIT -> prefs.getString("tp_alarm_sound_uri_v3913", "").orEmpty()
                TradeAlarmKind.STOP_LOSS -> prefs.getString("sl_alarm_sound_uri_v3913", "").orEmpty()
            }.ifBlank {
                customSoundUri.ifBlank { prefs.getString("verified_alert_sound_uri", "").orEmpty() }
            }

            ensureChannels(context)
            if (preset != AlarmTonePreset.VIBRATE_ONLY && volumePct > 0) {
                playDistinctToneSequence(context, kind, perKindUri, volumePct, preset)
            }
            if (vibrateEnabled) {
                vibratePattern(context, kind)
            }
            postHeadsUpNotification(context, kind, title, body)
        }
    }

    private fun playDistinctToneSequence(
        context: Context,
        kind: TradeAlarmKind,
        customSoundUri: String,
        volumePercent: Int = 90,
        preset: AlarmTonePreset = AlarmTonePreset.INSTITUTIONAL,
    ) {
        val safeVol = volumePercent.coerceIn(5, 100)
        // ۱. پخش الگوی صوتی متمایز برای هر یک از ۳ حالت (ورود / حد سود / حد ضرر) بر اساس پروفایل انتخابی کاربر
        if (preset != AlarmTonePreset.CUSTOM_ONLY) {
            runCatching {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, safeVol)
                when (preset) {
                    AlarmTonePreset.SOFT_CHIME -> {
                        when (kind) {
                            TradeAlarmKind.ENTRY -> {
                                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 220L)
                            }
                            TradeAlarmKind.TAKE_PROFIT -> {
                                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140) } }, 150L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 400L)
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
                                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 220) } }, 250L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 600L)
                            }
                            TradeAlarmKind.TAKE_PROFIT -> {
                                toneGen.startTone(ToneGenerator.TONE_SUP_CONFIRM, 220)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_CDMA_MED_SLS, 260) } }, 240L)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 280) } }, 520L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 900L)
                            }
                            TradeAlarmKind.STOP_LOSS -> {
                                toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 260)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 300) } }, 290L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 720L)
                            }
                        }
                    }

                    else -> {
                        when (kind) {
                            TradeAlarmKind.ENTRY -> {
                                // ۳ نت صعودی تند برای ورود به معامله
                                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 180) } }, 160L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 450L)
                            }
                            TradeAlarmKind.TAKE_PROFIT -> {
                                // ۴ نت شاد و پیروزمندانه برای رسیدن به حد سود (TP)
                                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_CDMA_MED_SLS, 220) } }, 180L)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_SUP_CONFIRM, 260) } }, 430L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 800L)
                            }
                            TradeAlarmKind.STOP_LOSS -> {
                                // ۲ بوق هشدار بم برای برخورد به حد ضرر (SL)
                                toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 240)
                                mainHandler.postDelayed({ runCatching { toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 280) } }, 280L)
                                mainHandler.postDelayed({ runCatching { toneGen.release() } }, 700L)
                            }
                        }
                    }
                }
            }
        }

        // ۲. اگر کاربر زنگ اختصاصی انتخاب کرده یا سیستم اجازه دهد، صدای نوتیفیکیشن را هم با ولوم تنظیم‌شده پخش کن
        runCatching {
            val uri = if (customSoundUri.isNotBlank()) {
                Uri.parse(customSoundUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ringtone != null) {
                    ringtone.volume = (safeVol / 100f).coerceIn(0.05f, 1.0f)
                }
                ringtone?.play()
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
