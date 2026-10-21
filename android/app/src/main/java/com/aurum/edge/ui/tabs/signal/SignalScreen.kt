package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.data.ScanRanking
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/**
 * صفحه معامله و سیگنال (Signal / Trading Tab) — آنچه واقعاً روی این صفحه رسم می‌شود:
 * ۱. برترین کاندید مجازِ اسکنر پس‌زمینه با تشریح شروط و تفکیک نماد/دسته دارایی
 * ۲. کارت موتورهای ALS-5 برای طلا، ALS-5S برای نقره، BTC-ARES و EUR-ARES
 * ۳. ثبت دستی معاملهٔ کاغذی برای نماد انتخاب‌شده (پیش‌نمایش → تأیید → ثبت اتمیک)
 * ۴. آخرین دلیل بررسیِ هر نماد از لاگ تصمیم‌ها (با امکان نمایش تاریخچه)
 */
@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState, onChartSymbol: (String) -> Unit) {
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
    val decisionLog by viewModel.decisionLog.collectAsStateWithLifecycle()
    var showAllDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var visibleDecisions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(12) }
    // Shared clock, not a private timer: expiring historical recommendations must not keep
    // a second `while(true)` alive for as long as this screen exists.
    val scanClock by viewModel.clock.collectAsStateWithLifecycle()
    val freshCandidates = ScanRanking.rank(scanState.statuses, com.aurum.edge.domain.Interval.M5, scanClock)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
        // The background scanner publishes each finished symbol immediately. Never require
        // the user to press Scan or wait for the entire sweep to display a fresh best pick.
        BestOpportunityDetailedCard(
            best = freshCandidates.firstOrNull(),
            sweeping = scanState.sweeping,
            onTrade = onChartSymbol,
        )
        Als5OpportunityMonitorCard(
            status = scanState.statuses.firstOrNull { it.symbol == "XAU/USD" },
            sweeping = scanState.sweeping,
        )
        // BTC has its own registry entry and its own records: no shared card, no shared score.
        BtcAresEnginesCard(
            status = scanState.statuses.firstOrNull { it.symbol == "BTC/USDT" },
            sweeping = scanState.sweeping,
        )
        // Silver likewise: ALS-5S shows its own engines, costs and no-trade reasons.
        SilverAresEnginesCard(
            status = scanState.statuses.firstOrNull { it.symbol == "XAG/USD" },
            sweeping = scanState.sweeping,
        )
        // EUR/USD likewise: EUR-ARES reports its own engines, pips, session clocks and refusals.
        EurAresEnginesCard(
            status = scanState.statuses.firstOrNull { it.symbol == "EUR/USD" },
            sweeping = scanState.sweeping,
        )

        // Manual paper ticket for the SELECTED symbol. It was fully implemented and guarded
        // (preview -> confirm -> atomic journal write) but no screen rendered it, so the only
        // reachable entry path was the automatic one. It needs `market` from the root, which
        // already collects it, so the ticket re-previews whenever the live price changes.
        PaperTicketSection(viewModel, market)

        SectionCard("دلیل بررسی") {
            if (decisionLog.isEmpty()) Text("هنوز بررسی ثبت نشده است")
            // Historical ai_* rows (the removed AI layer) are not the technical scan's latest score.
            val latestBySymbol = decisionLog.filter { !it.action.startsWith("ai_") }
                .distinctBy { it.symbol }
            if (decisionLog.size > latestBySymbol.size) OutlinedButton(onClick = { showAllDecisions = !showAllDecisions }) {
                Text(if (showAllDecisions) "آخرین دلیل هر نماد" else "تاریخچهٔ ${decisionLog.size} بررسی اخیر")
            }
            val records = if (showAllDecisions) decisionLog else latestBySymbol.ifEmpty { decisionLog.distinctBy { it.symbol } }
            records.take(visibleDecisions).forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${record.symbol} · ${record.interval} · ${formatTime(record.checkedAt)} · " +
                        "${record.action} · ${record.score?.toInt() ?: "—"}/۱۰۰",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onChartSymbol(record.symbol) })
                Text(record.reason, style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary)
                record.method?.let { method ->
                    Text("متد: $method · ${record.methodReason.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                record.layers.forEach { layer ->
                    Text("${layer.name}: ${layer.points ?: "—"}/۲۵ · ${layer.status} · ${layer.detail}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }
                }
            }
            if (records.size > visibleDecisions) OutlinedButton(onClick = { visibleDecisions += 12 }) {
                Text("نمایش ۱۲ بررسیٔ دیگر · ${records.size - visibleDecisions} باقی‌مانده")
            }
        }

    }
}

/**
 * کارت بهترین فرصت معاملاتی با تشریح کامل شروط تکنیکال و تفکیک نماد/دسته دارایی.
 */
@Composable
private fun BestOpportunityDetailedCard(
    best: PairScanStatus?,
    sweeping: Boolean,
    onTrade: (String) -> Unit,
) {
    val assetClass = best?.let { AssetClass.of(it.symbol) } ?: AssetClass.FOREX
    val isBuy = best?.action == SignalAction.BUY
    val actionColor = when (best?.action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.Gold
    }

    SectionCard(
        title = "برترین کاندید مجاز",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (best != null) {
                Pill(
                    text = if (isBuy) "خرید LONG" else "فروش SHORT",
                    color = actionColor,
                )
            }
        },
    ) {
        if (best != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header: Symbol + Category badge + Score + RR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = best.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                        )
                        Pill(assetClass.label, AurumColors.SurfaceAlt)
                        Pill("امتیاز ${best.confidence?.toInt() ?: 0}/۱۰۰", actionColor)
                    }
                    best.riskReward?.let { rr ->
                        Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                    }
                }

                // «متناسب با همان استراتژی»: which per-market method approved this symbol, so a
                // candidate is never shown without the strategy that makes it tradable.
                best.methodLabel?.let { method ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("متد این بازار (استراتژی مخصوص همان بازار)",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Pill(method, if (best.playbookAllowed == true) AurumColors.Cyan else AurumColors.Gold)
                    }
                }

                // «روند کلی بازار» برای همان کاندیدا: هم‌جهت بودن، یک شرطِ دیده‌شدنی است نه یک ادعا.
                best.trendLabel?.let { trend ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("روند اندازه‌گیری‌شدهٔ این نماد (تایم‌فریم مرجع)",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Pill(trend, if (best.trendAligned == true) AurumColors.Green else AurumColors.Gold)
                    }
                }
                best.trendNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                }

                // Price levels: Entry, SL, TP
                if (best.entry != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("نقطه ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            Text(formatPrice(best.entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        }
                        Column {
                            Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red.copy(alpha = 0.8f))
                            Text(formatPrice(best.stopLoss), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                        }
                        Column {
                            Text("تارگت سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green.copy(alpha = 0.8f))
                            Text(formatPrice(best.takeProfit), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                        }
                    }
                }

                Text(
                    text = best.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )

                // Detailed Technical Conditions Checklist for #1 Best Opportunity
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AurumColors.Surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .border(1.dp, AurumColors.Gold.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "📋 شروط و شواهد تکنیکال این فرصت (${best.symbol}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AurumColors.Gold,
                    )

                    if (best.conditions.isNotEmpty()) {
                        best.conditions.forEach { item ->
                            ConfluenceRow(item)
                        }
                    } else {
                        Text("شواهد تفصیلی از فید ثبت نشده‌اند؛ تأیید تکنیکال فرض نمی‌شود.",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                    }
                }

                // Action button: Trade this opportunity
                Button(
                    onClick = { onTrade(best.symbol) },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        "نمایش چارت ${best.symbol} · بررسی پیش از ورود",
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Text(if (sweeping) "در حال بررسی بازارها" else "کاندیدای مجازِ تازه‌ای موجود نیست",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
        }
    }
}
