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
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import java.util.Locale

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
            "سیگنال‌ها و معاملات باز و بسته‌شده",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "هشدار و جزییات لحظه‌ای خرید، فروش و تسویه معاملات"
            enableVibration(true)
            enableLights(true)
        }
        val research = NotificationChannel(CHANNEL_RESEARCH,
            "خبر پژوهشی · نه معامله", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "رویدادهای واقعی ناشر؛ تفسیر محدود، بدون سیگنال/معامله و بدون صدای ورود"
        }
        val systemTone = NotificationChannel(
            CHANNEL_VERIFIED_DEFAULT, "معاملات و فرصت‌های تاییدشده", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "اطلاعیه کامل باز شدن معاملات و فرصت‌های تاییدشده"
            enableVibration(true)
            enableLights(true)
        }
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
        val isBuy = item.action == SignalAction.BUY
        val side = if (isBuy) "خرید (LONG)" else "فروش (SHORT)"
        val assetClass = com.aurum.edge.core.AssetClass.of(item.symbol)
        val title = "فرصت معاملاتی $side ${item.symbol} · تایید شروط ایچیموکو"
        val text = "${item.symbol} ${item.interval.label} · ورود ${formatPrice(item.priceAtAlert)}$ · " +
            "SL ${formatPrice(item.stopLoss)} · TP ${formatPrice(item.takeProfit)}"
        val expanded = buildString {
            appendLine("📊 نماد کاندیدا: ${item.symbol} (${assetClass.label})")
            appendLine("🎯 جهت فرصت: $side")
            appendLine("💰 قیمت لحظه‌ای/ورود: ${formatPrice(item.priceAtAlert)}$")
            appendLine("🛑 حد ضرر (SL): ${formatPrice(item.stopLoss)}$")
            appendLine("🎯 حد سود (TP): ${formatPrice(item.takeProfit)}$")
            val rr = item.priceAction?.rewardRisk ?: 2.2
            appendLine("📐 نسبت ریسک به ریوارد: 1:${String.format(Locale.US, "%.1f", rr)}")
            appendLine("🔍 شواهد: ایچیموکو، آزادی ۲۴ دوره‌ای چیکواسپن و تراز MTF")
            appendLine("⏱ زمان: ${formatDateTime(item.signalBarTime)}")
        }.trimEnd()

        return postVerified(context, item.key.hashCode(), title, text, expanded, customSoundUri)
    }

    /**
     * Sends a rich, high-priority heads-up notification with complete details for EVERY trade opened
     * (BUY / SELL across Crypto, Forex, Commodity, Stocks), whether manual or automatic,
     * and whether the app is in the foreground or background.
     */
    fun notifyTradeOpened(context: Context, trade: PaperTrade, customSoundUri: String = ""): Boolean {
        ensureChannels(context)
        val isBuy = trade.action == SignalAction.BUY
        val sideFa = if (isBuy) "خرید" else "فروش"
        val symbol = trade.symbol
        val assetLabel = trade.assetClass.label

        val title = "معاملهٔ آموزشی $sideFa ثبت شد · ${trade.symbol} ($assetLabel)"
        val text = "${trade.symbol} ${trade.interval.label} · ورود ${formatPrice(trade.entry)}$ · شناسه ${trade.id.take(8)} · SL: ${formatPrice(trade.stopLoss)}$ · TP: ${formatPrice(trade.takeProfit)}$"

        val expanded = buildString {
            appendLine("📊 نماد معاملاتی: $symbol ($assetLabel)")
            appendLine("🎯 نوع پوزیشن: ${if (isBuy) "خرید (LONG)" else "فروش (SHORT)"}")
            appendLine("💰 قیمت ورود: ${formatPrice(trade.entry)}$")
            appendLine("🛑 حد ضرر (SL): ${formatPrice(trade.stopLoss)}$")
            appendLine("🎯 حد سود (TP): ${formatPrice(trade.takeProfit)}$")
            appendLine("⚡ اهرم معاملاتی: ${trade.effectiveLeverage}x | مارجین: $${formatPrice(trade.effectiveMarginUsd)}")
            appendLine("📐 نسبت ریسک به ریوارد (R:R): 1:${String.format(Locale.US, "%.1f", trade.riskReward ?: 2.2)}")
            appendLine("📦 حجم پوزیشن: ${String.format(Locale.US, "%.4f", trade.positionOz)} واحد")
            appendLine("🔍 استراتژی: ابر ایچیموکو (کومو ۸/۲۴/۷۲)، تقاطع TK، آزادی ۲۴ دوره‌ای چیکواسپن و تراز MTF")
            if (trade.entryConditions.isNotEmpty()) {
                val conditionsSummary = trade.entryConditions.take(6).joinToString("، ") {
                    it.name.substringAfter('·').trim()
                }
                appendLine("📋 شروط تاییدشده: $conditionsSummary")
            }
            if (trade.note.isNotBlank()) {
                appendLine("📝 توضیحات: ${trade.note}")
            }
            appendLine("⏱ زمان ورود: ${formatDateTime(trade.openedAt)}")
        }.trimEnd()

        return postVerified(
            context = context,
            id = trade.id.hashCode(),
            title = title,
            text = text,
            expanded = expanded,
            customSoundUri = customSoundUri,
        )
    }

    /** Caller must pass ONLY the new result of JournalStore.open, after its atomic write succeeds. */
    fun notifyRecordedAutoEntry(context: Context, trade: PaperTrade, customSoundUri: String): Boolean {
        if (!trade.autoOpened || !trade.isOpen ||
            trade.action == SignalAction.NO_TRADE || (trade.signalBarTime ?: 0L) <= 0L ||
            trade.mtf?.veto != false) return false
        val isLegacyEight = trade.symbol == "XAU/USD" && (trade.priceAction != null || trade.entryConditions.size == 8)
        if (isLegacyEight) {
            if (trade.priceAction == null || trade.entryConditions.size < 8 ||
                trade.entryConditions.take(8).any { it.status != "CONFIRMED" } ||
                trade.priceAction.barTime != trade.signalBarTime ||
                trade.priceAction.action != trade.action ||
                trade.priceAction.quote != trade.entry) return false
        } else {
            if (trade.entryConditions.size < 7 || trade.entryConditions.any { it.status == "CONFLICT" }) return false
        }
        return notifyTradeOpened(context, trade, customSoundUri)
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
            .setCategory(NotificationCompat.CATEGORY_EVENT)
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

    /**
     * Rich notification on trade close / settlement (TP, SL, or manual close)
     */
    fun notifyClosedTrade(context: Context, trade: PaperTrade) {
        ensureChannels(context)
        val pnl = trade.pnlUsd ?: 0.0
        val isProfit = pnl >= 0.0
        val sideFa = if (trade.action == SignalAction.BUY) "خرید (LONG)" else "فروش (SHORT)"
        val pnlFormatted = "${if (isProfit) "+" else ""}${String.format(Locale.US, "%.2f", pnl)}$"

        val title = if (isProfit) "🟢 تسویه با سود: ${trade.symbol} ($pnlFormatted)"
                    else "🔴 تسویه با ضرر: ${trade.symbol} ($pnlFormatted)"
        val text = "${trade.symbol} · $sideFa · خروج: ${formatPrice(trade.exitPrice)}$ · خالص: $pnlFormatted"

        val expanded = buildString {
            appendLine("📊 نماد معاملاتی: ${trade.symbol} (${trade.assetClass.label})")
            appendLine("🎯 نوع پوزیشن: $sideFa")
            appendLine("💰 قیمت ورود: ${formatPrice(trade.entry)}$")
            appendLine("🏁 قیمت خروج: ${formatPrice(trade.exitPrice)}$")
            appendLine("💵 سود/زیان خالص: $pnlFormatted")
            appendLine("⚡ اهرم: ${trade.effectiveLeverage}x | کارمزد: $${formatPrice(trade.effectiveCommissionUsd)} | اسپرد: $${formatPrice(trade.effectiveSpreadCostUsd)}")
            appendLine("📋 علت خروج: ${trade.exitReason ?: (if (isProfit) "برخورد با حد سود (TP)" else "برخورد با حد ضرر (SL)")}")
            appendLine("⏱ زمان ورود: ${formatDateTime(trade.openedAt)}")
            trade.closedAt?.let { appendLine("⏱ زمان خروج: ${formatDateTime(it)}") }
        }.trimEnd()

        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNALS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(trade.id.hashCode(), notification) }
    }
}

