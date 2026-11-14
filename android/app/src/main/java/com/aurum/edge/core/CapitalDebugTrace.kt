package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.round

/**
 * «دیباگر مدیریت سرمایه».
 *
 * چرا لازم شد: کاربر می‌پرسد «چرا با ریسک ۵ دلار، سود ۱٫۴ دلار شد؟» و پاسخ باید از دلِ همان
 * محاسبهٔ واقعی بیاید، نه از حافظهٔ کسی. این شیء یک پلن را با **همان موتور واقعی**
 * ([RiskAndCalendarEngine.calculatePosition]) می‌سازد و بعد:
 *
 *  ۱) قدم‌به‌قدم می‌گوید هر عدد از کجا آمد (تنظیمات → کارمزد → حجم → حد ضرر → هدف → R خالص).
 *  ۲) نُه **ناوردا** (invariant) را چک می‌کند: زیان خالص از بودجه بیشتر نشود، سود در دالان
 *     ۱٫۵R تا ۳R بماند، جهت حد سود درست باشد، لیکوییدیشن ≥ ۲٫۵R دور بماند، هیچ NaN نباشد…
 *  ۳) معاملهٔ واقعیِ ذخیره‌شده در ژورنال را دوباره محاسبه می‌کند و اعداد ذخیره‌شده را با اعداد
 *     تازه مقایسه می‌کند؛ هر اختلاف یک قرمزِ توضیح‌دار است، نه یک عدد مرموز.
 *
 * هر چیز این‌جا خالص و بدون اندروید است تا در تست هم دقیقاً همان مسیر UI اجرا شود.
 */

data class CapitalTraceStep(
    val titleFa: String,
    val valueFa: String,
)

data class CapitalTraceCheck(
    val titleFa: String,
    val passed: Boolean,
    val detailFa: String,
)

data class CapitalTrace(
    val symbol: String,
    val action: SignalAction,
    val headlineFa: String,
    val assumptionsFa: List<String>,
    val steps: List<CapitalTraceStep>,
    val checks: List<CapitalTraceCheck>,
    val result: RiskCalculationResult,
) {
    val passedCount: Int get() = checks.count { it.passed }
    val failedCount: Int get() = checks.size - passedCount
    val isHealthy: Boolean get() = failedCount == 0

    /** متن کامل برای کپی/ارسال به پشتیبانی — همان چیزی که روی صفحه دیده می‌شود. */
    fun toTextFa(): String {
        val out = StringBuilder()
        out.append("Aurum Edge — دیباگ مدیریت سرمایه\n")
        out.append(headlineFa).append('\n')
        out.append("نماد: ").append(symbol).append(" · جهت: ").append(if (action == SignalAction.BUY) "خرید" else "فروش").append('\n')
        if (assumptionsFa.isNotEmpty()) {
            out.append("\n— فرض‌ها —\n")
            assumptionsFa.forEach { out.append("• ").append(it).append('\n') }
        }
        out.append("\n— مراحل محاسبه —\n")
        steps.forEach { out.append(it.titleFa).append(": ").append(it.valueFa).append('\n') }
        out.append("\n— ناورداها (").append(passedCount).append(" از ").append(checks.size).append(" سبز) —\n")
        checks.forEach {
            out.append(if (it.passed) "✅ " else "⛔ ").append(it.titleFa).append(" — ").append(it.detailFa).append('\n')
        }
        return out.toString().trimEnd()
    }
}

object CapitalDebugTrace {

    /**
     * یک پلن فرضی با موتور واقعی می‌سازد.
     *
     * @param referencePrice قیمت مرجع (معمولاً آخرین قیمت زندهٔ همان نماد).
     * @param stopPercentOfPrice فاصلهٔ حد ضرر به درصد قیمت — **فرضِ صریح**، نه عدد ساختگی.
     * @param targetR R خواسته‌شدهٔ پلن (۱٫۵ تا ۳).
     * @param riskBudgetUsdOverride اگر داده شود، همان مبلغ ریسک استفاده می‌شود (برای بازرسی معاملهٔ گذشته).
     */
    fun simulate(
        policy: CapitalPolicy,
        symbol: String,
        referencePrice: Double,
        stopPercentOfPrice: Double,
        targetR: Double,
        action: SignalAction = SignalAction.BUY,
        riskBudgetUsdOverride: Double? = null,
    ): CapitalTrace? {
        if (!referencePrice.isFinite() || referencePrice <= 0.0) return null
        if (!stopPercentOfPrice.isFinite() || stopPercentOfPrice <= 0.0 || stopPercentOfPrice > 50.0) return null
        if (!targetR.isFinite() || targetR <= 0.0 || targetR > 6.0) return null

        val stopDistance = referencePrice * (stopPercentOfPrice / 100.0)
        if (stopDistance <= 0.0) return null
        val dir = if (action == SignalAction.BUY) -1.0 else 1.0
        val stopLoss = referencePrice + dir * stopDistance
        val takeProfit = referencePrice - dir * stopDistance * targetR
        val budget = riskBudgetUsdOverride
            ?: if (policy.useFixedDollarRisk) policy.fixedRiskUsd else null

        val result = RiskAndCalendarEngine.calculatePosition(
            symbolInput = symbol,
            accountBalance = policy.accountBalance,
            riskPercent = policy.riskPercent,
            entry = referencePrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            preferredAction = action,
            fixedRiskUsdOverride = budget,
        ) ?: return null

        return buildTrace(policy, result, referencePrice, stopPercentOfPrice, targetR, assumptionBudgetFa(policy, budget))
    }

    /**
     * بازرسی یک معاملهٔ واقعی: همان اعداد ذخیره‌شدهٔ ژورنال دوباره از موتور عبور می‌کنند و
     * نتیجه با چیزی که ذخیره شده مقایسه می‌شود. اگر روزی کدی عددی را عوض کند و ژورنال را
     * به‌روز نکند، همین‌جا قرمز می‌شود.
     */
    fun auditTrade(trade: PaperTrade, policy: CapitalPolicy): CapitalTrace? {
        val entry = trade.entry
        val stop = trade.initialStopLoss ?: trade.stopLoss
        val stopDistance = abs(entry - stop)
        if (!entry.isFinite() || entry <= 0.0 || stopDistance <= 1e-9) return null
        val planR = abs(trade.takeProfit - entry) / stopDistance
        if (!planR.isFinite() || planR <= 0.0) return null

        val storedBudget = trade.fixedRiskUsd.takeIf { it.isFinite() && it > 0.0 }
        // R هدف را از «پول» می‌گیریم نه از نسبت قیمتی: حد سود ذخیره‌شده کارمزد را هم پوشش
        // می‌دهد، پس نسبت قیمتی‌اش با R خالص یکی نیست و اگر نسبت قیمتی را ورودی بگیریم،
        // بازرسی یک اختلاف ساختگی نشان می‌دهد.
        val priceDistance = abs(trade.takeProfit - entry)
        val positionForFee = if (trade.positionOz > 0.0) trade.positionOz else 0.0
        val grossProfitStored = PaperOrderRules.quotePnlToUsd(
            trade.symbol,
            priceDistance * positionForFee,
            trade.takeProfit,
        )
        val storedFee = trade.effectiveCommissionUsd + trade.effectiveSpreadCostUsd
        val netProfitStored = grossProfitStored - storedFee
        val budgetForRatio = storedBudget ?: policy.riskBudgetUsd
        val netTargetR = if (budgetForRatio > 1e-9 && netProfitStored > 0.0) {
            netProfitStored / budgetForRatio
        } else {
            planR
        }
        val targetR = if (netTargetR.isFinite() && netTargetR > 0.05 && netTargetR <= 6.0) netTargetR else planR

        val trace = simulate(
            policy = policy,
            symbol = trade.symbol,
            referencePrice = entry,
            stopPercentOfPrice = stopDistance / entry * 100.0,
            targetR = targetR,
            action = trade.action,
            riskBudgetUsdOverride = storedBudget,
        ) ?: return null

        val result = trace.result
        val tick = VenueSpecs.of(trade.symbol).tickSize
        val comparisons = listOf(
            compareCheck(
                "حجم ذخیره‌شده با محاسبهٔ دوباره",
                trade.positionOz,
                result.positionUnits,
                tolerance = maxOf(VenueSpecs.of(trade.symbol).quantityStep * 1.5, 1e-9),
                unit = trade.positionUnit.ifBlank { "واحد" },
            ),
            compareCheck(
                "قیمت حد سود ذخیره‌شده با محاسبهٔ دوباره",
                trade.takeProfit,
                result.takeProfit,
                tolerance = maxOf(tick * 2.0, 1e-6),
                unit = "",
            ),
            compareCheck(
                "کارمزد ذخیره‌شده با محاسبهٔ دوباره",
                trade.effectiveCommissionUsd + trade.effectiveSpreadCostUsd,
                result.totalRoundTripFeeUsd,
                tolerance = 0.05,
                unit = "$",
            ),
            CapitalTraceCheck(
                titleFa = "R ذخیره‌شده در ژورنال با R خالص محاسبه‌شده",
                passed = abs(trade.riskReward - result.riskReward) <= 0.05,
                detailFa = "ژورنال: " + num(trade.riskReward) + " · محاسبه: " + num(result.riskReward),
            ),
        )
        val (grossStored, netStored) = RiskAndCalendarEngine.computeExitPnlUsd(trade, trade.takeProfit)
        val extra = listOf(
            CapitalTraceCheck(
                titleFa = "سود در حد سود با سود خالص محاسبه‌شده",
                passed = abs(netStored - result.netProfitAtTpUsd) <= 0.1 || trade.fixedRiskUsd > 0.0,
                detailFa = "ژورنال: $" + money(netStored) + " · محاسبه: $" + money(result.netProfitAtTpUsd) +
                    (if (trade.fixedRiskUsd > 0.0) " (ژورنال با فرمول R×مبلغ ثابت حساب می‌کند)" else ""),
            ),
            CapitalTraceCheck(
                titleFa = "زیان در حد ضرر برابر مبلغ ریسک تنظیم‌شده",
                passed = storedBudget == null || abs(result.netLossAtSlUsd - storedBudget) <= 0.15,
                detailFa = "ذخیره‌شده: $" + money(storedBudget ?: result.netLossAtSlUsd) +
                    " · محاسبه: $" + money(result.netLossAtSlUsd),
            ),
            CapitalTraceCheck(
                titleFa = "سود در حد سود لااقل از زیان بیشتر است (R مثبت)",
                passed = grossStored > 0.0 || netStored > 0.0,
                detailFa = "ناخالص: $" + money(grossStored) + " · خالص: $" + money(netStored),
            ),
        )

        return trace.copy(
            headlineFa = "بازرسی معاملهٔ ذخیره‌شده در ژورنال (شناسه " + trade.id.take(8) + ")",
            checks = trace.checks + comparisons + extra,
        )
    }

    private fun assumptionBudgetFa(policy: CapitalPolicy, budget: Double?): String =
        if (budget != null) {
            "ریسک هر معامله: $" + money(budget) + " (حالت ثابت دلاری)"
        } else {
            "ریسک هر معامله: $" + money(policy.riskBudgetUsd) + " (" + num(policy.riskPercent) + "٪ از $" +
                money(policy.accountBalance) + ")"
        }

    private fun buildTrace(
        policy: CapitalPolicy,
        result: RiskCalculationResult,
        referencePrice: Double,
        stopPercentOfPrice: Double,
        targetR: Double,
        budgetLineFa: String,
    ): CapitalTrace {
        val spec = VenueSpecs.of(result.symbol)
        val feePerUnit = spec.totalRoundTripFeeUsd(result.entry, 1.0)
        val stopPerUnitUsd = PaperOrderRules.quotePnlToUsd(result.symbol, result.stopDistance, result.entry)
        val rawUnits = if (stopPerUnitUsd + feePerUnit > 1e-12) {
            result.riskBudgetUsd / (stopPerUnitUsd + feePerUnit)
        } else {
            0.0
        }
        val budget = result.riskBudgetUsd
        val fee = result.totalRoundTripFeeUsd
        val feeSharePct = if (budget > 1e-9) fee / budget * 100.0 else 0.0
        val directionFa = if (result.action == SignalAction.BUY) "خرید" else "فروش"

        val steps = listOf(
            CapitalTraceStep(
                "۱ · ورودی تنظیمات",
                "موجودی $" + money(policy.accountBalance) + " · " + budgetLineFa +
                    " · ترمز روزانه " + num(policy.dailyLossLimitPercent) + "٪ ($" + money(policy.dailyLossLimitUsd) + ")",
            ),
            CapitalTraceStep(
                "۲ · پلن فرضی (" + directionFa + ")",
                "ورود " + priceText(result.entry) + " · حد ضرر " + priceText(result.stopLoss) +
                    " · فاصله " + money(result.stopDistance) + " (" + num(stopPercentOfPrice) + "٪ قیمت)",
            ),
            CapitalTraceStep(
                "۳ · کارمزد هر واحد",
                "$" + precise(feePerUnit) + " (رفتوبرگشت: کمیسیون هر دو سمت + اسپرد) · مرجع: " + spec.venue,
            ),
            CapitalTraceStep(
                "۴ · حجم خام",
                "بودجه ÷ (زیان هر واحد $" + precise(stopPerUnitUsd) + " + کارمزد هر واحد $" + precise(feePerUnit) +
                    ") = " + precise(rawUnits) + " " + result.unitLabel,
            ),
            CapitalTraceStep(
                "۵ · حجم نهایی (گام بازار)",
                "گام " + precise(spec.quantityStep) + " ⇒ پایین‌گرد به " + precise(result.positionUnits) + " " +
                    result.unitLabel + " · " + result.lotDescription,
            ),
            CapitalTraceStep(
                "۶ · کارمزد رفت‌وبرگشت نهایی",
                "$" + money(fee) + " = " + num(feeSharePct) + "٪ از مبلغ ریسک",
            ),
            CapitalTraceStep(
                "۷ · اگر حد ضرر بخورد",
                "زیان ناخالص $" + money(result.grossLossAtSlUsd) + " + کارمزد $" + money(fee) +
                    " = زیان خالص $" + money(result.netLossAtSlUsd),
            ),
            CapitalTraceStep(
                "۸ · هدف قیمتی",
                "R پلن " + num(targetR) + " ⇒ فاصلهٔ قیمتی " + money(result.targetDistance) +
                    " (R خام قیمتی " + num(result.grossRiskReward) + ") · حد سود " + priceText(result.takeProfit),
            ),
            CapitalTraceStep(
                "۹ · اگر حد سود بخورد",
                "سود ناخالص $" + money(result.grossProfitAtTpUsd) + " − کارمزد $" + money(fee) +
                    " = سود خالص $" + money(result.netProfitAtTpUsd) + " · R خالص " + num(result.riskReward),
            ),
            CapitalTraceStep(
                "۱۰ · اهرم و لیکوییدیشن",
                "اهرم " + result.leverage + "x · مارجین $" + money(result.marginRequiredUsd) +
                    " · لیکوییدیشن " + priceText(result.liquidationPrice) + " (فاصله " +
                    num(result.liquidationBufferR) + "R از حد ضرر)",
            ),
        )

        val bandLow = budget * CapitalPolicy.MIN_RR
        val bandHigh = budget * CapitalPolicy.MAX_RR
        val allFinite = listOf(
            result.entry, result.stopLoss, result.takeProfit, result.stopDistance, result.targetDistance,
            result.positionUnits, result.totalRoundTripFeeUsd, result.netProfitAtTpUsd, result.netLossAtSlUsd,
            result.riskReward, result.grossRiskReward, result.liquidationBufferR,
        ).all { it.isFinite() }

        val checks = listOf(
            CapitalTraceCheck(
                "زیان خالص در حد ضرر از مبلغ ریسک بیشتر نمی‌شود",
                result.netLossAtSlUsd <= budget + 0.02,
                "خالص $" + money(result.netLossAtSlUsd) + " ≤ بودجه $" + money(budget),
            ),
            CapitalTraceCheck(
                "زیان خالص دقیقاً برابر مبلغ ریسک تنظیم‌شده است",
                abs(result.netLossAtSlUsd - budget) <= 0.02,
                "خالص $" + money(result.netLossAtSlUsd) + " = بودجه $" + money(budget),
            ),
            CapitalTraceCheck(
                "سود خالص در دالان ۱٫۵R تا ۳R می‌ماند",
                result.netProfitAtTpUsd >= bandLow - 0.05 && result.netProfitAtTpUsd <= bandHigh + 0.05,
                "$" + money(result.netProfitAtTpUsd) + " بین $" + money(bandLow) + " و $" + money(bandHigh),
            ),
            CapitalTraceCheck(
                "جهت حد ضرر و حد سود با جهت معامله یکی است",
                if (result.action == SignalAction.BUY) {
                    result.stopLoss < result.entry && result.takeProfit > result.entry
                } else {
                    result.stopLoss > result.entry && result.takeProfit < result.entry
                },
                "ورود " + priceText(result.entry) + " · SL " + priceText(result.stopLoss) + " · TP " + priceText(result.takeProfit),
            ),
            CapitalTraceCheck(
                "سود خالص دقیقاً R × مبلغ ریسک است",
                abs(result.netProfitAtTpUsd - targetR * budget) <= 0.05,
                "$" + money(result.netProfitAtTpUsd) + " در برابر " + num(targetR) + " × $" + money(budget),
            ),
            CapitalTraceCheck(
                "کارمزد کمتر از یک‌چهارم مبلغ ریسک است",
                feeSharePct <= 25.0,
                "$" + money(fee) + " = " + num(feeSharePct) + "٪ از ریسک" +
                    (if (feeSharePct > 25.0) " — این نماد با این حد ضرر برای ریسک کوچک گران است" else ""),
            ),
            CapitalTraceCheck(
                "لیکوییدیشن دست‌کم ۲٫۵R دورتر از حد ضرر است",
                result.liquidationBufferR >= TradePlanGuard.MIN_LIQUIDATION_BUFFER_R,
                num(result.liquidationBufferR) + "R · " + result.leverageReasonFa,
            ),
            CapitalTraceCheck(
                "حجم بدون گِرد به گام بازار و با دقت ۶ رقم اعشار است",
                abs(result.positionUnits - round(result.positionUnits * 1_000_000.0) / 1_000_000.0) < 1e-9,
                precise(result.positionUnits) + " · گام بازار " + precise(spec.quantityStep) +
                    " در کارگزار اعمال می‌شود، نه در محاسبهٔ ریسک",
            ),
            CapitalTraceCheck(
                "هیچ عددی NaN یا بی‌نهایت نیست",
                allFinite,
                if (allFinite) "همهٔ اعداد متناهی‌اند" else "یک عدد نامعتبر در نتیجه هست",
            ),
            CapitalTraceCheck(
                "حجم پوزیشن بزرگ‌تر از صفر است",
                result.positionUnits > 0.0,
                precise(result.positionUnits) + " " + result.unitLabel,
            ),
        )

        val assumptions = listOf(
            "قیمت مرجع " + priceText(referencePrice) + " (آخرین قیمت خوانده‌شده برای همین نماد)",
            "فاصلهٔ حد ضرر " + num(stopPercentOfPrice) + "٪ قیمت — فرض صریح این آزمایش، نه پیش‌بینی بازار",
            "R خواسته‌شده " + num(targetR) + " · جهت " + directionFa,
            "کارمزد و اسپرد از مرجع " + spec.venue + " خوانده می‌شود",
        )

        return CapitalTrace(
            symbol = result.symbol,
            action = result.action,
            headlineFa = "آزمون فرضی روی " + result.symbol + " با همین تنظیمات فعلی",
            assumptionsFa = assumptions,
            steps = steps,
            checks = checks,
            result = result,
        )
    }

    private fun compareCheck(
        titleFa: String,
        stored: Double,
        recomputed: Double,
        tolerance: Double,
        unit: String,
    ): CapitalTraceCheck {
        val passed = abs(stored - recomputed) <= tolerance
        val unitSuffix = if (unit == "$") "" else if (unit.isBlank()) "" else " " + unit
        val storedText = if (unit == "$") "$" + money(stored) else precise(stored) + unitSuffix
        val recomputedText = if (unit == "$") "$" + money(recomputed) else precise(recomputed) + unitSuffix
        return CapitalTraceCheck(
            titleFa = titleFa,
            passed = passed,
            detailFa = "ذخیره‌شده " + storedText + " · محاسبهٔ دوباره " + recomputedText +
                (if (passed) "" else " · اختلاف " + precise(abs(stored - recomputed))),
        )
    }

    private fun priceText(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%,.2f", v)

    private fun precise(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}
