package com.aurum.edge.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aurum.edge.MainActivity
import com.aurum.edge.R
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.FOREX_CALENDAR_SOURCE_URL
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.ui.components.formatPrice

object Notifier {

    const val CHANNEL_MONITOR = "aurum_monitor"
    const val CHANNEL_SIGNALS = "aurum_signals"
    const val CHANNEL_RESEARCH = "aurum_research_news_v1"
    const val CHANNEL_VERIFIED_DEFAULT = "aurum_verified_system_v1"
    const val CHANNEL_VERIFIED_FILE = "aurum_verified_file_v1"
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
        val research = NotificationChannel(CHANNEL_RESEARCH,
            "خبر پژوهشی · نه معامله", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "رویدادهای واقعی ناشر؛ تفسیر محدود، بدون سیگنال/معامله و بدون صدای ورود"
        }
        val systemTone = NotificationChannel(
            CHANNEL_VERIFIED_DEFAULT, "فرصت آموزشی · صدای سیستم", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "تنها شرط‌های ۹/۹ تاییدشده؛ معاملهٔ واقعی نیست" }
        val fileTone = NotificationChannel(
            CHANNEL_VERIFIED_FILE, "فرصت آموزشی · فایل صوتی گوشی", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "اعلان بدون صدای سیستمی؛ اپ فقط فایل صوتی انتخابی را کوتاه پخش می‌کند"
            // Channels are immutable on Android 8+. SystemUI cannot read an app's private SAF
            // grant, so use a silent channel and play the file in our foreground service instead.
            setSound(null, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(signals)
        manager.createNotificationChannel(research)
        manager.createNotificationChannel(systemTone)
        manager.createNotificationChannel(fileTone)
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

    /** Separate informational channel: a publisher observation is NEVER an entry/candidate alert. */
    fun notifyResearch(context: Context, evidenceId: String, title: String, text: String): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_RESEARCH)?.importance?.let {
                it > NotificationManager.IMPORTANCE_NONE
            } != true) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_RESEARCH)
            .setContentTitle(title.take(110))
            .setContentText(text.take(220))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(5100 + ((evidenceId.hashCode() and 0x7fffffff) % 400), notification)
            true
        }.getOrDefault(false)
    }

    fun canNotifyVerified(context: Context, customSoundUri: String): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = if (customSoundUri.isNotBlank() && AlertSoundPlayer.canOpen(context, customSoundUri))
            CHANNEL_VERIFIED_FILE else CHANNEL_VERIFIED_DEFAULT
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(channel)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true
    }

    /** User-initiated diagnostic: test the REAL Android notification channel, not just MediaPlayer. */
    fun notifyTest(context: Context, customSoundUri: String): Boolean = postVerified(
        context, 4209, "آزمون اعلان Aurum Edge", "آزمایش مجوز، کانال و صدای گوشی",
        "این اعلان آزمایشی است؛ هیچ سیگنال، معامله یا سفارش واقعی ثبت نشده است.", customSoundUri,
    )

    /** Only after the candidate is durably saved. This alert NEVER claims a trade was opened. */
    fun notifyVerifiedOpportunity(context: Context, item: PaperOpportunity, customSoundUri: String): Boolean {
        val title = when (item.action) {
            SignalAction.BUY -> "فرصت آموزشی خرید XAU/USD · ۹/۹"
            SignalAction.SELL -> "فرصت آموزشی فروش XAU/USD · ۹/۹"
            SignalAction.NO_TRADE -> return false
        }
        val text = "${item.interval.label} · قیمت ${formatPrice(item.priceAtAlert)}$ · " +
            "SL ${formatPrice(item.stopLoss)} · TP ${formatPrice(item.takeProfit)}"
        if (item.priceAction?.barTime != item.signalBarTime ||
            item.priceAction?.action != item.action ||
            item.newsEvidence.calendarSource != FOREX_CALENDAR_SOURCE_URL ||
            item.newsEvidence.calendarCheckedAt == null) return false
        return postVerified(context, item.key.hashCode(), title, text,
            "$text\nکاندیدا؛ باز شدن پوزیشن کاغذی یا سفارش واقعی را نشان نمی‌دهد. جزئیات در ژورنال.",
            customSoundUri)
    }

    /** Caller must pass ONLY the new result of JournalStore.open, after its atomic write succeeds. */
    fun notifyRecordedAutoEntry(context: Context, trade: PaperTrade, customSoundUri: String): Boolean {
        if (!trade.autoOpened || !trade.isOpen || trade.symbol != "XAU/USD" ||
            trade.action == SignalAction.NO_TRADE || (trade.signalBarTime ?: 0L) <= 0L ||
            trade.newsEvidence?.evidence.isNullOrEmpty() ||
            trade.newsEvidence?.calendarSource != FOREX_CALENDAR_SOURCE_URL ||
            trade.newsEvidence?.calendarCheckedAt == null || trade.mtf?.veto != false ||
            trade.entryConditions.size != 9 ||
            trade.entryConditions.any { it.status != "CONFIRMED" } ||
            trade.entryConditions[8].name != NewsConfluence.NEWS_LABEL ||
            trade.priceAction?.barTime != trade.signalBarTime ||
            trade.priceAction?.action != trade.action ||
            trade.priceAction?.quote != trade.entry) return false
        val side = if (trade.action == SignalAction.BUY) "خرید" else "فروش"
        val title = "معاملهٔ آموزشی $side ثبت شد · فقط کاغذی"
        val text = "XAU/USD ${trade.interval.label} · ورود ${formatPrice(trade.entry)}$ · شناسه ${trade.id.take(8)}"
        return postVerified(context, trade.id.hashCode(), title, text,
            "$text\nSL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)} · " +
                "۹/۹، خبر و شواهد رنج/ICT در ژورنال ثبت شدند. سفارش واقعی ارسال نشد.", customSoundUri)
    }

    private fun postVerified(context: Context, id: Int, title: String, text: String,
                             expanded: String, customSoundUri: String): Boolean {
        if (!canNotifyVerified(context, customSoundUri)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val custom = customSoundUri.isNotBlank() && AlertSoundPlayer.canOpen(context, customSoundUri)
        val channel = if (custom) CHANNEL_VERIFIED_FILE else CHANNEL_VERIFIED_DEFAULT
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context))
            .build()
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.getOrDefault(false)
        // On Android 11+ a user's channel sound choice (including mute) overrides our app clip.
        val userChoseChannelSound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            manager.getNotificationChannel(channel)?.hasUserSetSound() == true
        val channelAllowsAudio = (manager.getNotificationChannel(channel)?.importance ?: 0) >=
            NotificationManager.IMPORTANCE_DEFAULT
        if (posted && custom && !userChoseChannelSound && channelAllowsAudio)
            AlertSoundPlayer.play(context, customSoundUri)
        return posted
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
