package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.MarketHours
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction

data class PairScanStatus(
    val symbol: String,
    val state: String,
    val detail: String,
    val interval: Interval? = null,
    val lastScanAt: Long? = null,
    val action: SignalAction? = null,
    val assetClass: AssetClass = AssetClass.of(symbol),
)

data class PairScanState(
    val statuses: List<PairScanStatus> = emptyList(),
    val sweeping: Boolean = false,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
)

object CatalogQuotePolicy {
    fun tickMonitored(symbol: String): Boolean = symbol in WatchCatalog.scannerSymbols &&
        (AssetClass.of(symbol) == AssetClass.FOREX || symbol in setOf("XAU/USD", "XAG/USD", "DOW") ||
            CryptoCatalog.isCrypto(symbol))
}

/** A missing position is not a market-off indication. Describe only measured gates. */
object MarketEntryReason {
    fun forClass(asset: AssetClass, scan: PairScanState, enabled: Boolean,
                 now: Long = System.currentTimeMillis()): String {
        if (!enabled) return "ورود خودکار خاموش"
        val rows = scan.statuses.filter { it.assetClass == asset }
        if (rows.isEmpty() || rows.all { it.state == "pending" }) return "در انتظار ارزیابی نمادها"
        val scheduledOpen = rows.filterNot { MarketHours.closedFor(it.symbol, now) }
        if (scheduledOpen.isEmpty()) return "سشن نمادهای بررسی‌شده بسته است"
        val executable = scheduledOpen.filter { CatalogQuotePolicy.tickMonitored(it.symbol) }
        if (executable.isEmpty()) return "فقط اسکن؛ تیک هم‌نماد ورود/خروج ندارد"
        val current = executable.filter { it.lastScanAt?.let { at -> now - at in 0..600_000L } == true }
        if (current.isEmpty()) return "در انتظار ارزیابی تازه"
        if (current.any { it.state == "candidate" || it.state == "opened" })
            return "کاندیدا؛ تأیید تیک و ریسک ورود لازم است"
        if (current.any { it.state == "watch" }) return "فشردگی رصد شد؛ هنوز شکست تأیید نشده"
        if (current.any { it.state == "no_signal" }) return "کراس، فشردگی یا سایر شروط ورود کامل نیست"
        if (current.any { it.state == "blocked" }) return "گیت ورود/ریسک: ${current.first { it.state == "blocked" }.detail.take(72)}"
        if (current.any { it.state == "partial" || it.state == "error" })
            return "دادهٔ لازم برای سیگنال کامل نشده"
        return "ورود هنوز تأیید نشده"
    }
}
