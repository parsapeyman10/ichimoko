package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * «مدیریت سرمایه» باید یک تعریف داشته باشد و همان تعریف هم قابل بازرسی باشد.
 *
 * این تست‌ها سه چیز را قید می‌کنند:
 *  ۱) عدد ریسک و دامنهٔ هدف (۱٫۵R تا ۳R) از یک‌جا محاسبه می‌شود.
 *  ۲) هیچ عددی بی‌صدا اصلاح نمی‌شود: هر برش، یک [CapitalFinding] با کد مشخص دارد.
 *  ۳) پاک‌سازی idempotent است و روی ورودی خراب هرگز NaN/بی‌نهایت تولید نمی‌کند.
 */
class CapitalPolicyTest {

    private fun assertHealthy(policy: CapitalPolicy) {
        val values = listOf(
            policy.accountBalance, policy.initialCapital, policy.targetCapital, policy.riskPercent,
            policy.fixedRiskUsd, policy.dailyLossLimitPercent, policy.maxDrawdownLimitPercent,
            policy.riskBudgetUsd, policy.rewardFloorUsd, policy.rewardCeilingUsd,
        )
        values.forEach { assertTrue("هیچ عددی نباید NaN/بی‌نهایت باشد: $it", it.isFinite()) }
    }

    @Test
    fun `default settings mean five dollars of risk and a seven fifty to fifteen dollar target`() {
        val policy = CapitalPolicy.from(AppSettings())
        assertHealthy(policy)
        assertTrue("حالت پیش‌فرض ریسک ثابت دلاری است", policy.useFixedDollarRisk)
        assertEquals(5.0, policy.riskBudgetUsd, 1e-9)
        assertEquals(10.0, policy.rewardFloorUsd, 1e-9)
        assertEquals(15.0, policy.rewardCeilingUsd, 1e-9)
        assertEquals(0.005, policy.riskBudgetPctOfEquity, 1e-9) // ۵ دلار از ۱۰۰٬۰۰۰ = ۰٫۰۰۵٪
    }

    @Test
    fun `percent mode derives the budget from the balance`() {
        // درصد موجودی فقط وقتی ملاک است که هیچ مبلغ ثابت معتبری تنظیم نشده باشد؛
        // با مبلغ ثابت، همان مبلغ حاکم است (قانون «دلار دقیق» کاربر).
        val percentOnly = CapitalPolicy(
            accountBalance = 2_000.0,
            riskPercent = 1.5,
            useFixedDollarRisk = false,
            fixedRiskUsd = 0.0,
        )
        assertEquals(30.0, percentOnly.riskBudgetUsd, 1e-9)
        assertEquals(60.0, percentOnly.rewardFloorUsd, 1e-9)
        assertEquals(90.0, percentOnly.rewardCeilingUsd, 1e-9)

        val withFixedAmount = percentOnly.copy(fixedRiskUsd = 5.0)
        assertEquals(
            "درصد موجودی نباید مبلغ ثابت را کوچک یا بزرگ کند",
            5.0,
            withFixedAmount.riskBudgetUsd,
            1e-9,
        )
        // تنها اصلاح مجاز، بالا بردن مبلغ ثابتِ صفر تا کف `$0.50` است؛ درصد دست‌نخورده می‌ماند.
        assertTrue(
            "درصد معتبر نباید اصلاح شود",
            percentOnly.sanitized().fixes.all { it.code == "fixed_risk_below_min" },
        )
    }

    @Test
    fun `sanitize explains every correction instead of changing numbers silently`() {
        val dirty = CapitalPolicy(
            accountBalance = 5.0,
            initialCapital = Double.NaN,
            targetCapital = 10.0,
            riskPercent = 25.0,
            useFixedDollarRisk = true,
            fixedRiskUsd = 0.1,
            dailyLossLimitPercent = 500.0,
            maxDrawdownLimitPercent = 0.2,
            maxOpenTrades = 99,
            maxOpenTradesPerSymbol = 9,
        )
        val cleaned = dirty.sanitized()
        val p = cleaned.policy
        assertTrue("باید اصلاح‌ها گزارش شوند", cleaned.fixes.size >= 5)
        assertTrue(cleaned.changed)

        assertEquals(CapitalPolicy.MIN_BALANCE, p.accountBalance, 1e-9)
        assertEquals(p.accountBalance, p.initialCapital, 1e-9)
        assertEquals(100_000.0, p.targetCapital, 1e-9)
        assertEquals(CapitalPolicy.MAX_RISK_PERCENT, p.riskPercent, 1e-9)
        assertEquals(CapitalPolicy.MIN_FIXED_RISK, p.fixedRiskUsd, 1e-9)
        assertEquals(CapitalPolicy.MAX_DAILY_LIMIT_PERCENT, p.dailyLossLimitPercent, 1e-9)
        assertEquals(CapitalPolicy.MIN_DRAWDOWN_LIMIT_PERCENT, p.maxDrawdownLimitPercent, 1e-9)
        assertEquals(CapitalPolicy.MAX_OPEN_TRADES, p.maxOpenTrades)
        assertEquals(CapitalPolicy.MAX_OPEN_TRADES_PER_SYMBOL, p.maxOpenTradesPerSymbol)
        assertHealthy(p)

        // هر اصلاح یک کد پایدار دارد (برای تست و لاگ)، نه پیام آزاد.
        val codes = cleaned.fixes.map { it.code }.toSet()
        assertTrue("کد موجودی نامعتبر", codes.contains("balance_below_min"))
        assertTrue("کد درصد ریسک", codes.contains("risk_percent_out_of_range"))
        assertTrue("کد ریسک ثابت", codes.contains("fixed_risk_below_min"))
        assertTrue("کد ترمز روزانه", codes.contains("daily_limit_out_of_range"))
        assertTrue("کد افت سرمایه", codes.contains("drawdown_limit_out_of_range"))
        cleaned.fixes.forEach { assertTrue("هر پیام باید متن داشته باشد", it.messageFa.isNotBlank()) }

        // بار دوم هیچ اصلاحی ندارد: idempotent
        val second = p.sanitized()
        assertTrue("پاک‌سازی باید idempotent باشد، بود: ${second.fixes.map { it.code }}", second.fixes.isEmpty())
        assertEquals(p, second.policy)
    }

    @Test
    fun `risk budget is capped at half of the equity so one loss cannot empty the account`() {
        val policy = CapitalPolicy(accountBalance = 100.0, fixedRiskUsd = 90.0, useFixedDollarRisk = true)
        val cleaned = policy.sanitized()
        assertEquals(50.0, cleaned.policy.fixedRiskUsd, 1e-9)
        assertEquals("fixed_risk_above_cap", cleaned.fixes.first { it.code == "fixed_risk_above_cap" }.code)
    }

    @Test
    fun `findings warn when a single trade risks more than two percent`() {
        val danger = CapitalPolicy(accountBalance = 100.0, fixedRiskUsd = 5.0, useFixedDollarRisk = true)
        val found = danger.findings()
        assertTrue(found.any { it.code == "risk_above_five_pct" && it.severity == CapitalSeverity.DANGER })

        val warning = CapitalPolicy(accountBalance = 200.0, fixedRiskUsd = 5.0, useFixedDollarRisk = true)
        assertTrue(warning.findings().any { it.code == "risk_above_two_pct" && it.severity == CapitalSeverity.WARNING })

        val healthy = CapitalPolicy(accountBalance = 100_000.0, fixedRiskUsd = 5.0, useFixedDollarRisk = true)
        assertFalse(healthy.findings().any { it.code.startsWith("risk_above") })
    }

    @Test
    fun `findings say plainly that percent mode is inert while fixed mode is on`() {
        val policy = CapitalPolicy(
            accountBalance = 100_000.0,
            riskPercent = 2.0,
            useFixedDollarRisk = true,
            fixedRiskUsd = 5.0,
        )
        val finding = policy.findings().firstOrNull { it.code == "percent_mode_inert" }
        assertTrue("باید توضیح داده شود که درصد اعمال نمی‌شود", finding != null)
        assertTrue(finding!!.messageFa.contains("5.00"))
        assertTrue(finding.messageFa.contains("2,000.00")) // معادل درصد در همان لحظه
    }

    @Test
    fun `findings flag a daily brake that fits only one loss and a switched off brake`() {
        val tight = CapitalPolicy(
            accountBalance = 100.0,
            fixedRiskUsd = 5.0,
            dailyLossLimitPercent = 5.0,
            useFixedDollarRisk = true,
        )
        assertTrue("۵ دلار ریسک با ترمز ۵ دلاری = یک باخت", tight.findings().any { it.code == "daily_brake_too_tight" })

        val off = CapitalPolicy(accountBalance = 100.0, dailyLossLimitPercent = 100.0)
        assertTrue(off.isDailyBrakeOff)
        assertTrue(off.findings().any { it.code == "daily_brake_off" })
    }

    @Test
    fun `findings flag open risk that exceeds the daily brake`() {
        val policy = CapitalPolicy(
            accountBalance = 100.0,
            fixedRiskUsd = 5.0,
            dailyLossLimitPercent = 10.0, // ۱۰ دلار
            maxOpenTrades = 4, // ۴ × ۵ = ۲۰ دلار
            useFixedDollarRisk = true,
        )
        assertTrue(policy.findings().any { it.code == "open_risk_above_daily_limit" })
    }

    @Test
    fun `random garbage policies always sanitize to finite in-range values`() {
        val random = Random(20261114)
        repeat(300) {
            val dirty = CapitalPolicy(
                accountBalance = random.nextDouble(-1_000.0, 1_000_000.0),
                initialCapital = random.nextDouble(-500.0, 500_000.0),
                targetCapital = random.nextDouble(-1_000.0, 1_000_000.0),
                riskPercent = random.nextDouble(-50.0, 500.0),
                useFixedDollarRisk = random.nextBoolean(),
                fixedRiskUsd = random.nextDouble(-10.0, 10_000.0),
                dailyLossLimitPercent = random.nextDouble(-10.0, 200.0),
                maxDrawdownLimitPercent = random.nextDouble(-10.0, 200.0),
                maxOpenTrades = random.nextInt(-5, 50),
                maxOpenTradesPerSymbol = random.nextInt(-5, 20),
            )
            val p = dirty.sanitized().policy
            assertHealthy(p)
            assertTrue("موجودی حداقل", p.accountBalance >= CapitalPolicy.MIN_BALANCE)
            assertTrue("درصد ریسک در دامنه", p.riskPercent in CapitalPolicy.MIN_RISK_PERCENT..CapitalPolicy.MAX_RISK_PERCENT)
            assertTrue("ریسک ثابت حداقل", p.fixedRiskUsd >= CapitalPolicy.MIN_FIXED_RISK)
            assertTrue("ریسک ثابت حداکثر نیمی از موجودی", p.fixedRiskUsd <= p.accountBalance * 0.5 + 1e-6)
            assertTrue("ترمز روزانه در دامنه", p.dailyLossLimitPercent in 0.5..100.0)
            assertTrue("افت سرمایه در دامنه", p.maxDrawdownLimitPercent in 1.0..100.0)
            assertTrue("سقف پوزیشن در دامنه", p.maxOpenTrades in 0..12 && p.maxOpenTradesPerSymbol in 0..4)
            assertTrue("پاک‌سازی دوباره نباید چیزی را عوض کند", p.sanitized().fixes.isEmpty())
        }
    }
}
