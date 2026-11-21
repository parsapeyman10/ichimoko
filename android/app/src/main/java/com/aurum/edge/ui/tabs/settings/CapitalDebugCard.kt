package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.CapitalDebugTrace
import com.aurum.edge.core.CapitalFinding
import com.aurum.edge.core.CapitalPolicy
import com.aurum.edge.core.CapitalSeverity
import com.aurum.edge.core.CapitalTrace
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.money
import com.aurum.edge.core.num
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

private val DEBUG_STOP_PERCENTS = listOf(0.25, 0.5, 1.0, 2.0)
private val DEBUG_TARGET_RS = listOf(1.5, 2.0, 2.5, 3.0)

/** رنگ هر شدت — یک‌جا تعریف می‌شود تا کارت و فهرست یافته‌ها هم‌رنگ بمانند. */
private fun severityColor(severity: CapitalSeverity): Color = when (severity) {
    CapitalSeverity.INFO -> AurumColors.Cyan
    CapitalSeverity.WARNING -> AurumColors.Orange
    CapitalSeverity.DANGER -> AurumColors.Red
}

private fun severityIcon(severity: CapitalSeverity): String = when (severity) {
    CapitalSeverity.INFO -> "ℹ️"
    CapitalSeverity.WARNING -> "⚠️"
    CapitalSeverity.DANGER -> "⛔"
}

/**
 * فهرست یافته‌های مدیریت سرمایه: هر اصلاح و هر هشدار، با رنگ خودش.
 * این بلوک هم داخل کارت اصلی سرمایه دیده می‌شود و هم بالای کارت دیباگ.
 */
@Composable
internal fun CapitalFindingsBlock(findings: List<CapitalFinding>) {
    if (findings.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        findings.forEach { finding ->
            Text(
                text = severityIcon(finding.severity) + " " + finding.messageFa,
                style = MaterialTheme.typography.labelSmall,
                color = severityColor(finding.severity),
            )
        }
    }
}

/**
 * کارت «دیباگ مدیریت سرمایه».
 *
 * دو حالت دارد:
 *  ۱) **آزمون فرضی**: با آخرین قیمت زندهٔ نماد فعال و یک فاصلهٔ حد ضررِ اعلام‌شده، همان موتور
 *     واقعی را اجرا می‌کند و قدم‌به‌قدم می‌گوید هر عدد از کجا آمد.
 *  ۲) **بازرسی معاملهٔ آخر**: اعداد ذخیره‌شده در ژورنال را دوباره حساب می‌کند؛ هر اختلاف قرمز است.
 */
@Composable
internal fun CapitalDebugCard(
    settings: AppSettings,
    symbol: String,
    referencePrice: Double?,
    lastTrade: PaperTrade?,
    trades: List<PaperTrade>,
) {
    val policy = remember(settings, trades) { CapitalPolicy.from(settings).copy(
        useFixedDollarRisk = true,
        fixedRiskUsd = com.aurum.edge.core.ProfitRiskPolicy.riskUsd(trades, settings.accountBalance),
    ) }
    val findings = remember(policy) { policy.findings() }
    val context = LocalContext.current

    var auditMode by rememberSaveable { mutableStateOf(false) }
    var stopPercent by rememberSaveable { mutableStateOf(0.5) }
    var targetR by rememberSaveable { mutableStateOf(2.0) }
    var copied by remember { mutableStateOf(false) }

    val trace: CapitalTrace? = remember(policy, symbol, referencePrice, stopPercent, targetR, auditMode, lastTrade) {
        if (auditMode) {
            lastTrade?.let { CapitalDebugTrace.auditTrade(it, policy) }
        } else {
            referencePrice?.let {
                CapitalDebugTrace.simulate(
                    policy = policy,
                    symbol = symbol,
                    referencePrice = it,
                    stopPercentOfPrice = stopPercent,
                    targetR = targetR,
                )
            }
        }
    }

    val worst = findings.maxByOrNull { if (it.severity == CapitalSeverity.DANGER) 2 else if (it.severity == CapitalSeverity.WARNING) 1 else 0 }
        ?.severity

    SectionCard(
        title = "🧪 دیباگ مدیریت سرمایه",
        initiallyExpanded = false,
        trailing = {
            val pillText = when {
                worst == CapitalSeverity.DANGER -> "تنظیم خطرناک"
                worst == CapitalSeverity.WARNING -> "هشدار"
                trace == null -> "آماده"
                trace.isHealthy -> "${trace.passedCount}/${trace.checks.size} سبز"
                else -> "${trace.failedCount} ایراد"
            }
            val pillColor = when {
                worst == CapitalSeverity.DANGER -> AurumColors.Red
                worst == CapitalSeverity.WARNING -> AurumColors.Orange
                trace == null -> AurumColors.TextMuted
                trace.isHealthy -> AurumColors.Green
                else -> AurumColors.Red
            }
            Pill(text = pillText, color = pillColor)
        },
    ) {
        // ── ۱) یافته‌های خودِ تنظیمات (مستقل از نماد) ─────────────────────────────
        Text(
            text = "مبنای ریسک: " + (if (policy.useFixedDollarRisk) {
                "۵ دلار + ۰٫۵٪ سود واجد شرایط = $" + money(policy.riskBudgetUsd)
            } else {
                num(policy.riskPercent) + "٪ × $" + money(policy.accountBalance) + " = $" + money(policy.riskBudgetUsd)
            }) + " · هدف: $" + money(policy.riskBudgetUsd * 2.0) + " تا $" + money(policy.rewardCeilingUsd),
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        CapitalFindingsBlock(findings)

        // ── ۲) انتخاب حالت بازرسی ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DebugModeChip(
                label = "آزمون فرضی",
                selected = !auditMode,
                onClick = { auditMode = false; copied = false },
                modifier = Modifier.weight(1f),
            )
            DebugModeChip(
                label = if (lastTrade == null) "بازرسی معاملهٔ آخر (خالی)" else "بازرسی معاملهٔ آخر",
                selected = auditMode,
                enabled = lastTrade != null,
                onClick = { if (lastTrade != null) { auditMode = true; copied = false } },
                modifier = Modifier.weight(1f),
            )
        }

        if (!auditMode) {
            if (referencePrice == null) {
                Text(
                    text = "برای " + symbol + " هنوز قیمت زنده‌ای خوانده نشده است. یک بار تب رصد را باز کن " +
                        "تا آزمون فرضی با قیمت واقعی همین نماد اجرا شود (هیچ قیمتی از خودمان نمی‌سازیم).",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Orange,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = "قیمت مرجع " + symbol + ": " + String.format(java.util.Locale.US, "%,.2f", referencePrice),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "فاصلهٔ حد ضرر فرضی (٪ قیمت):",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ChipRow {
                    DEBUG_STOP_PERCENTS.forEach { pct ->
                        DebugValueChip(
                            text = num(pct) + "٪",
                            selected = kotlin.math.abs(stopPercent - pct) < 1e-9,
                            onClick = { stopPercent = pct; copied = false },
                        )
                    }
                }
                Text(
                    "R خواسته‌شده (هدف ÷ ریسک):",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ChipRow {
                    DEBUG_TARGET_RS.forEach { r ->
                        DebugValueChip(
                            text = num(r) + "R",
                            selected = kotlin.math.abs(targetR - r) < 1e-9,
                            onClick = { targetR = r; copied = false },
                        )
                    }
                }
            }
        }

        // ── ۳) گزارش ─────────────────────────────────────────────────────────────
        if (trace == null) {
            Text(
                text = "هنوز چیزی برای نمایش نیست: یا قیمت مرجع خوانده نشده یا پلن با این ورودی‌ها معتبر نیست.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = trace.headlineFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                trace.steps.forEach { step ->
                    Column {
                        Text(
                            text = step.titleFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = step.valueFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }
            }

            Text(
                text = "ناورداها: " + trace.passedCount + " سبز · " + trace.failedCount + " قرمز",
                style = MaterialTheme.typography.labelSmall,
                color = if (trace.isHealthy) AurumColors.Green else AurumColors.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                trace.checks.forEach { check ->
                    Text(
                        text = (if (check.passed) "✅ " else "⛔ ") + check.titleFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (check.passed) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = check.detailFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
            }

            Button(
                onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Aurum capital debug", trace.toTextFa()),
                    )
                    copied = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (copied) AurumColors.Green else AurumColors.SurfaceAlt,
                    contentColor = if (copied) Color.Black else AurumColors.TextPrimary,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    text = if (copied) "✅ گزارش کپی شد" else "📋 کپی گزارش کامل برای ارسال به پشتیبانی",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun DebugModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
            .border(1.dp, if (selected) AurumColors.Gold else AurumColors.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> AurumColors.TextMuted
                selected -> AurumColors.Gold
                else -> AurumColors.TextPrimary
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DebugValueChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
            .border(1.dp, if (selected) AurumColors.Gold else AurumColors.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
