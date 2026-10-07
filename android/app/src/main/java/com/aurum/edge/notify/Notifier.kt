package com.aurum.edge.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aurum.edge.MainActivity
import com.aurum.edge.R
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatPriceFor
import java.util.Locale

/**
 * Android system notification delivery with exact channel separation:
 * 1. CHANNEL_VERIFIED_DEFAULT / CHANNEL_VERIFIED_FILE: High-priority heads-up alerts on every trade entry & opportunity
 * 2. CHANNEL_SERVICE: Foreground service persistent status notification
 * 3. CHANNEL_RESEARCH: Background informational notifications
 */
object Notifier {
    const val CHANNEL_SERVICE = "aurum_edge_service_channel"
    const val CHANNEL_VERIFIED_DEFAULT = "aurum_edge_verified_default"
    const val CHANNEL_VERIFIED_FILE = "aurum_edge_verified_file"
    const val CHANNEL_RESEARCH = "aurum_edge_research"
    const val MONITOR_NOTIFICATION_ID = 4201

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "سرویس پایش پس‌زمینه",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "وضعیت زنده پایش بازار و اتصال فید داده"
            setShowBadge(false)
        }

        val verifiedDefault = NotificationChannel(
            CHANNEL_VERIFIED_DEFAULT,
            "معاملات و فرصت‌های تاییدشده (صدای پیش‌فرض)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "اعلان ورود به معاملات و فرصت‌های آموزشی تاییدشده با صدای سیستم"
            enableVibration(true)
            setShowBadge(true)
        }

        val verifiedFile = NotificationChannel(
            CHANNEL_VERIFIED_FILE,
            "معاملات و فرصت‌های تاییدشده (صدای انتخابی)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "اعلان ورود به معاملات با پخش فایل صوتی سفارشی کاربر"
            setSound(null, null)
            enableVibration(true)
            setShowBadge(true)
        }

        val researchChannel = NotificationChannel(
            CHANNEL_RESEARCH,
            "دیده‌بان بازار و پژوهش",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "اطلاع‌رسانی تغییرات روند و رخدادهای بازار"
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(serviceChannel, verifiedDefault, verifiedFile, researchChannel))
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

    fun buildForegroundServiceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("دیده‌بان خودکار Aurum Edge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun buildMonitorNotification(context: Context, text: String): Notification =
        buildForegroundServiceNotification(context, text)

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
        val text = "${item.symbol} ${item.interval.label} · ورود ${formatPriceFor(item.symbol, item.priceAtAlert)}$ · " +
            "SL ${formatPriceFor(item.symbol, item.stopLoss)} · TP ${formatPriceFor(item.symbol, item.takeProfit)}"
        val expanded = buildString {
            appendLine("📊 نماد کاندیدا: ${item.symbol} (${assetClass.label})")
            appendLine("🎯 جهت فرصت: $side")
            appendLine("💰 قیمت لحظه‌ای/ورود: ${formatPriceFor(item.symbol, item.priceAtAlert)}$")
            appendLine("🛑 حد ضرر (SL): ${formatPriceFor(item.symbol, item.stopLoss)}$")
            appendLine("🎯 حد سود (TP): ${formatPriceFor(item.symbol, item.takeProfit)}$")
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
        val text = "${trade.symbol} ${trade.interval.label} · ورود ${formatPriceFor(trade.symbol, trade.entry)}$ · شناسه ${trade.id.take(8)} · SL: ${formatPriceFor(trade.symbol, trade.stopLoss)}$ · TP: ${formatPriceFor(trade.symbol, trade.takeProfit)}$"

        val expanded = buildString {
            appendLine("📊 نماد معاملاتی: $symbol ($assetLabel)")
            appendLine("🎯 نوع پوزیشن: ${if (isBuy) "خرید (LONG)" else "فروش (SHORT)"}")
            appendLine("💰 قیمت ورود: ${formatPriceFor(trade.symbol, trade.entry)}$")
            appendLine("🛑 حد ضرر (SL): ${formatPriceFor(trade.symbol, trade.stopLoss)}$")
            appendLine("🎯 حد سود (TP): ${formatPriceFor(trade.symbol, trade.takeProfit)}$")
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

    /**
     * Sends a rich notification when an open trade is closed / settled (TP, SL, or manual close).
     */
    fun notifyClosedTrade(context: Context, trade: PaperTrade, customSoundUri: String = ""): Boolean {
        ensureChannels(context)
        val pnl = trade.pnlUsd ?: 0.0
        val isWin = pnl >= 0.0
        val pnlFormatted = (if (isWin) "+$" else "-$") + String.format(Locale.US, "%.2f", kotlin.math.abs(pnl))
        val exitPriceFormatted = trade.exitPrice?.let { formatPriceFor(trade.symbol, it) } ?: "—"
        val entryPriceFormatted = formatPriceFor(trade.symbol, trade.entry)
        val sideFa = if (trade.action == SignalAction.BUY) "خرید" else "فروش"

        val title = "${if (isWin) "✅ بسته‌شدن با سود" else "🛑 بسته‌شدن با زیان"} · ${trade.symbol} ($pnlFormatted)"
        val text = "${trade.symbol} ($sideFa) بسته شد · قیمت خروج: $exitPriceFormatted$ · PnL: $pnlFormatted · ${trade.exitReason ?: "تسویه معامله"}"

        val expanded = buildString {
            appendLine("📊 نماد: ${trade.symbol} (${trade.assetClass.label})")
            appendLine("🎯 نوع پوزیشن: $sideFa")
            appendLine("💰 قیمت ورود: $entryPriceFormatted$")
            appendLine("🚪 قیمت خروج: $exitPriceFormatted$")
            appendLine("💵 سود/زیان نهایی: $pnlFormatted")
            appendLine("📋 علت خروج: ${trade.exitReason ?: "تسویه معامله"}")
            appendLine("⚡ اهرم: ${trade.effectiveLeverage}x | مارجین: $${formatPrice(trade.effectiveMarginUsd)}")
            trade.closedAt?.let { appendLine("⏱ زمان بسته‌شدن: ${formatDateTime(it)}") }
        }.trimEnd()

        return postVerified(
            context = context,
            id = trade.id.hashCode() xor 0x5f5f,
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
        if (posted && custom) {
            AlertSoundPlayer.play(context, customSoundUri)
        }
        return posted
    }
}
