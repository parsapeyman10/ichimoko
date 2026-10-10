package com.aurum.edge.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.min

/** V1 paper-only entry policy. The authoritative call is inside JournalStore's mutex.
 * Closed trades, including those from an earlier installation, are retained for daily-loss and
 * consecutive-loss checks. A missing/invalid ledger or risk value must never authorize entry.
 */
object PaperPortfolioPolicy {
    const val MAX_OPEN = 4
    const val MAX_PER_CLASS = 2
    const val MAX_RISK_PER_TRADE = 0.005
    const val MAX_DAILY_LOSS = 0.02
    const val LOSS_STREAK_LIMIT = 3

    fun blocker(trades: List<PaperTrade>, symbol: String, balance: Double,
                addedRiskUsd: Double, signalBarTime: Long? = null,
                now: Long = System.currentTimeMillis(), openingBalance: Double? = null): String? {
        // Oil entry quarantine: the reported loss series cannot be audited without the device
        // journal and venue-matched history. No new paper entry (manual or automatic) is safe to
        // authorize yet. This intentionally does NOT alter or close existing trades.
        OilEntrySafety.blocker(symbol)?.let { return it }
        val open = trades.filter { it.isOpen }
        if (open.size >= MAX_OPEN) return "سقف ۴ پوزیشن باز پورتفو پر است"
        if (open.any { it.symbol == symbol }) return "برای این نماد پوزیشن کاغذی باز است"
        val group = AssetClass.of(symbol)
        if (open.count { AssetClass.of(it.symbol) == group } >= MAX_PER_CLASS)
            return "سقف ۲ پوزیشن دستهٔ «${group.label}» پر است"
        if (signalBarTime != null && trades.any { it.symbol == symbol && it.signalBarTime == signalBarTime })
            return "این نماد و کندل پیش‌تر معامله شده‌اند"
        if (!balance.isFinite() || balance <= 0.0 || !addedRiskUsd.isFinite() || addedRiskUsd < 0.0 ||
            open.any { !it.riskUsd.isFinite() || it.riskUsd < 0.0 ||
                !it.effectiveCommissionUsd.isFinite() || it.effectiveCommissionUsd < 0.0 ||
                !it.effectiveSpreadCostUsd.isFinite() || it.effectiveSpreadCostUsd < 0.0 } ||
            addedRiskUsd > balance * MAX_RISK_PER_TRADE + 1e-4)
            return "ریسک هر معامله نباید از ۰٫۵٪ موجودی بیشتر شود"

        // Emergency halt does not silently reset at midnight: a *winning closed* trade breaks it.
        val streak = trades.filter { !it.isOpen && it.closedAt != null && it.pnlUsd != null }
            .sortedWith(compareByDescending<PaperTrade> { it.closedAt }.thenByDescending { it.id })
            .takeWhile { (it.pnlUsd ?: 0.0) < 0.0 }.size
        if (streak >= LOSS_STREAK_LIMIT) return "توقف اضطراری: ۳ باخت بستهٔ متوالی"

        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val today = trades.filter { !it.isOpen && it.closedAt?.let { at ->
            at <= now && Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate() == day
        } == true }
        if (today.any { it.pnlUsd?.isFinite() != true }) return "نتیجهٔ معاملات امروز نامشخص است"
        val realizedLoss = today.sumOf { -min(it.pnlUsd ?: 0.0, 0.0) }
        // Production uses SettingsStore's immutable UTC-day opening equity. The fallback is
        // for pure callers/tests without persistent settings; never use it to raise a live cap.
        val openingEquity = openingBalance ?: (balance - today.sumOf { it.pnlUsd ?: 0.0 })
        if (!openingEquity.isFinite() || openingEquity <= 0.0 ||
            realizedLoss + open.sumOf { it.riskUsd + it.effectiveCommissionUsd + it.effectiveSpreadCostUsd } + addedRiskUsd >
                openingEquity * MAX_DAILY_LOSS + 1e-4)
            return "سقف ضرر روزانهٔ ۲٪ با احتساب ریسک پوزیشن‌های باز و ورود جدید پر است"
        return null
    }
}
