package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.CapitalPolicy
import com.aurum.edge.core.NeuralDepthAudit
import com.aurum.edge.core.NeuralDepthReport
import com.aurum.edge.core.NeuralDepthTargets
import com.aurum.edge.core.NeuralStateCell
import com.aurum.edge.core.NeuralStateVerdict
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun verdictColor(verdict: NeuralStateVerdict) = when (verdict) {
    NeuralStateVerdict.PROFITABLE -> AurumColors.Green
    NeuralStateVerdict.WEAK -> AurumColors.Orange
    NeuralStateVerdict.LOSING -> AurumColors.Red
    NeuralStateVerdict.BLOCKED_BY_FEE -> AurumColors.Orange
    NeuralStateVerdict.GATHERING, NeuralStateVerdict.UNSEEN -> AurumColors.TextMuted
}

private fun verdictIcon(verdict: NeuralStateVerdict) = when (verdict) {
    NeuralStateVerdict.PROFITABLE -> "✅"
    NeuralStateVerdict.WEAK -> "⚠️"
    NeuralStateVerdict.LOSING -> "⛔"
    NeuralStateVerdict.BLOCKED_BY_FEE -> "💸"
    NeuralStateVerdict.GATHERING -> "…"
    NeuralStateVerdict.UNSEEN -> "·"
}

private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

/**
 * کارت «عمق یادگیری» — فقط عدد، بدون توضیح.
 *
 * شبکهٔ ۱۸۰حالتی (فاز × سشن × نوسان × دالان R) از معامله‌های واقعیِ بسته‌شده پر می‌شود و هر
 * خانه در برابر «قانون تنظیمات» (ریسک ۵ دلار، هدف ۱٫۵–۳R) داوری می‌شود.
 */
@Composable
internal fun NeuralDepthCard(
    trades: List<PaperTrade>,
    settings: AppSettings,
) {
    val report by produceState<NeuralDepthReport?>(initialValue = null, trades, settings) {
        delay(80L)
        value = withContext(Dispatchers.Default) {
            runCatching {
                NeuralDepthAudit.fromTrades(
                    trades = trades,
                    targets = NeuralDepthTargets.from(CapitalPolicy.from(settings).copy(
                        fixedRiskUsd = com.aurum.edge.core.ProfitRiskPolicy.riskUsd(trades, settings.accountBalance),
                    )),
                )
            }.getOrNull()
        }
    }

    val r = report
    val pillText = when {
        r == null -> "در حال سنجش"
        r.scoredTrades == 0 -> "بدون نمونه"
        r.overallExpectancyR >= NeuralDepthAudit.PROFITABLE_EXPECTANCY_R -> "سودده"
        r.overallExpectancyR > 0 -> "سربه‌سر"
        else -> "زیان‌ده"
    }
    val pillColor = when {
        r == null -> AurumColors.TextMuted
        r.scoredTrades == 0 -> AurumColors.TextMuted
        r.overallExpectancyR >= NeuralDepthAudit.PROFITABLE_EXPECTANCY_R -> AurumColors.Green
        r.overallExpectancyR > 0 -> AurumColors.Orange
        else -> AurumColors.Red
    }

    SectionCard(
        title = "🧠 عمق یادگیری · همهٔ حالت‌ها (برد و باخت)",
        initiallyExpanded = false,
        trailing = { Pill(text = pillText, color = pillColor) },
    ) {
        if (r == null) {
            Text(
                text = "…",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "نمونه",
                value = r.scoredTrades.toString(),
                valueColor = AurumColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "نرخ برد / لازم",
                value = pct(r.overallWinRatePct) + " / " + pct(r.requiredWinRatePct),
                valueColor = if (r.overallWinRatePct >= r.requiredWinRatePct) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "امید ریاضی",
                value = (if (r.overallExpectancyR >= 0) "+" else "") +
                    String.format(Locale.US, "%.2fR", r.overallExpectancyR),
                valueColor = if (r.overallExpectancyR >= 0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "پوشش حالت‌ها",
                value = r.cellsObserved.toString() + "/" + r.latticeSize + " (" + pct(r.coveragePct) + ")",
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "اثبات‌شده / ضعیف / بازنده",
                value = r.provenCells.toString() + " / " + r.weakCells.toString() + " / " + r.losingCells.toString(),
                valueColor = when {
                    r.losingCells > 0 -> AurumColors.Red
                    r.provenCells > 0 -> AurumColors.Green
                    else -> AurumColors.TextSecondary
                },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = r.verdictFa,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                r.overallExpectancyR >= NeuralDepthAudit.PROFITABLE_EXPECTANCY_R -> AurumColors.Green
                r.overallExpectancyR > 0 -> AurumColors.Orange
                else -> AurumColors.Red
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            r.lawProgressFa.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
        }

        if (r.worstCells.isNotEmpty()) {
            Text(
                text = "بدترین حالت‌ها",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            r.worstCells.forEach { cell -> CellRow(cell) }
        }
        if (r.bestCells.isNotEmpty()) {
            Text(
                text = "بهترین حالت‌ها",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Green,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            r.bestCells.forEach { cell -> CellRow(cell) }
        }

        if (r.lossDriversFa.isNotEmpty() || r.winDriversFa.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                r.winDriversFa.forEach { line ->
                    Text("✅ " + line, style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                }
                r.lossDriversFa.forEach { line ->
                    Text("⛔ " + line, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                }
            }
        }

        if (r.unseenExamplesFa.isNotEmpty()) {
            Text(
                text = "حالت‌های ندیده (" + (r.latticeSize - r.cellsObserved) + ")",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            r.unseenExamplesFa.forEach { line ->
                Text(
                    text = "· " + line,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun CellRow(cell: NeuralStateCell) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = verdictIcon(cell.verdict) + " " + cell.labelFa,
                style = MaterialTheme.typography.labelSmall,
                color = verdictColor(cell.verdict),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "×" + String.format(Locale.US, "%.2f", cell.sizeMultiplier),
                style = MaterialTheme.typography.labelSmall,
                color = if (cell.sizeMultiplier <= 0.0) AurumColors.Red else AurumColors.TextSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = cell.samples.toString() + " نمونه · برد " + pct(cell.winRatePct) + " (لازم " +
                pct(cell.requiredWinRatePct) + ") · امید " +
                (if (cell.expectancyR >= 0) "+" else "") + String.format(Locale.US, "%.2fR", cell.expectancyR),
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
