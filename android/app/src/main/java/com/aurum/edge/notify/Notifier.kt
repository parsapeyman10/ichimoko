package com.aurum.edge.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aurum.edge.MainActivity
import com.aurum.edge.R
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.ui.components.formatPrice

object Notifier {

    const val CHANNEL_MONITOR = "aurum_monitor"
    const val CHANNEL_SIGNALS = "aurum_signals"
    const val MONITOR_NOTIFICATION_ID = 4201

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.monitor_channel_desc)
        }
        val signals = NotificationChannel(
            CHANNEL_SIGNALS,
            "سیگنال‌های معاملاتی",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "هشدار سیگنال تاییدشده طلا (فقط دیتای واقعی)"
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(signals)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildMonitorNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setContentTitle(context.getString(R.string.monitor_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun notifySignal(context: Context, signal: Signal) {
        val title = when (signal.action) {
            SignalAction.BUY -> "سیگنال خرید XAU/USD"
            SignalAction.SELL -> "سیگنال فروش XAU/USD"
            SignalAction.NO_TRADE -> return
        }
        val text = buildString {
            append("${signal.interval.label} · امتیاز ${signal.confidence.toInt()}/100")
            signal.entry?.let { append(" · ورود ${formatPrice(it)}") }
            signal.stopLoss?.let { append(" · SL ${formatPrice(it)}") }
            signal.takeProfit?.let { append(" · TP ${formatPrice(it)}") }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNALS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + signal.reasons.take(3).joinToString(" • ")))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(signal.barTime.toInt(), notification) }
    }

    fun notifyClosedTrade(context: Context, trade: PaperTrade) {
        val pnl = trade.pnlUsd ?: 0.0
        val title = if (pnl >= 0) "پوزیشن کاغذی با سود بسته شد" else "پوزیشن کاغذی با ضرر بسته شد"
        val text = "${trade.action.name} ${trade.interval.label} · خروج ${formatPrice(trade.exitPrice)} · " +
            "${if (pnl >= 0) "+" else ""}${String.format("%.2f", pnl)}$ · ${trade.exitReason ?: ""}"
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNALS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(trade.id.hashCode(), notification) }
    }
}
