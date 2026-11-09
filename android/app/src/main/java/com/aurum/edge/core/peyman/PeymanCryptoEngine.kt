package com.aurum.edge.core.peyman

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.TradePlanGuard
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max

/**
 * # موتور تصمیم «پیمان کریپتو حرفه‌ای»
 *
 * زنجیرهٔ تصمیم (هر مرحله می‌تواند وتوی سخت بدهد):
 *
 * ```
 * سلامت داده → رژیم بازار → شمارش اصلی/جایگزین نئوویو → انتخاب ستاپ
 *   → BOS/بازآزمایی (بدون تعقیب) → زمینهٔ BTC → کیفیت اجرا و هزینه
 *   → TradePlanGuard → ریسک پرتفوی و خوشه → امتیاز کیفیت → تصمیم
 * ```
 *
 * قواعد تغییرناپذیر:
 * - نئوویو به‌تنهایی ورود نمی‌سازد؛ بدون BOS/بازآزمایی معتبر ورودی وجود ندارد.
 * - هیچ وتوی سختی با امتیاز بالا خنثی نمی‌شود.
 * - در حالت `SHADOW` تصمیم کامل ثبت می‌شود ولی `isTradeAllowed=false` است.
 * - حالت `DISABLED` هیچ ارزیابی‌ای انجام نمی‌دهد (هزینهٔ صفر و رفتار قبلی دست‌نخورده).
 */
object PeymanCryptoEngine {

    fun decide(input: PeymanCryptoInput): PeymanCryptoDecision {
        val cfg = input.config
        if (!cfg.isActive) return PeymanCryptoDecision.disabled()

        val symbol = input.symbol.trim().uppercase(java.util.Locale.ROOT)
        val assetClass = AssetClass.of(symbol)
        if (assetClass != AssetClass.CRYPTO) {
            return reject(
                input,
                "این استراتژی فقط روی رمزارز اجرا می‌شود (نماد: ${input.symbol}).",
                setup = PeymanSetupType.NONE,
                status = PeymanSetupStatus.BLOCKED,
            )
        }

        // ۱) سلامت داده
        val health = CryptoDataHealthGate.check(input)
        if (!health.isHealthy) {
            return reject(
                input,
                health.reasonsFa.firstOrNull() ?: "دادهٔ ورودی سالم نیست.",
                setup = PeymanSetupType.NONE,
                status = PeymanSetupStatus.BLOCKED,
                health = health,
            )
        }

        // ۲) حجم: در بازار بدون حجم معتبر، «تأیید حجم» ساختگی ممنوع است.
        // مسیر ورود شکست بدون حجم معتبر باز نمی‌شود (نه اینکه حجم فرضی ساخته شود).
        if (!health.volumeAvailable) {
            return reject(
                input,
                "حجم معتبر در دسترس نیست؛ ورودِ مبتنی بر تأیید حجم مجاز نیست.",
                status = PeymanSetupStatus.BLOCKED,
                health = health,
            )
        }

        // ۳) رژیم بازار — هم کندل جاری و هم کندل قبلی.
        // کندل شکست خودش «انبساط» است؛ اگر فقط کندل جاری دیده شود، لحظهٔ فشردگی همیشه
        // از دست می‌رود و ادعای «فشردگی → انبساط» عملاً غیرقابل‌اجراست.
        val regime = CryptoMarketRegimeEngine.detect(input.triggerBars, cfg)
        val triggerClosedForRegime = PeymanMath.closed(input.triggerBars)
        val priorRegime = if (triggerClosedForRegime.size > cfg.minBars) {
            CryptoMarketRegimeEngine.detect(triggerClosedForRegime.dropLast(1), cfg)
        } else {
            null
        }
        val priorCompression = priorRegime?.regime == CryptoMarketRegime.COMPRESSION
        if (regime.regime == CryptoMarketRegime.ILLIQUID_SHOCK) {
            return reject(input, "رژیم شوک بی‌نقدشوندگی؛ ورود قطعاً ممنوع است.", status = PeymanSetupStatus.BLOCKED, regime = regime, health = health)
        }
        if (regime.regime == CryptoMarketRegime.UNKNOWN && !priorCompression) {
            return reject(input, "رژیم بازار نامشخص است؛ حدس زدن ممنوع.", status = PeymanSetupStatus.BLOCKED, regime = regime, health = health)
        }

        // ۳) شمارش‌ها
        val ensemble = NeoWaveCountEnsemble.build(
            strategyBars = input.strategyBars,
            triggerBars = input.triggerBars,
            regime = regime.regime,
            atrPercentile = regime.atrPercentile,
            config = cfg,
        )
        val primary = ensemble.primary
        if (primary == null) {
            return reject(
                input,
                "هیچ شمارش نئوویوی معتبری وجود ندارد؛ نئوویو به‌تنهایی هم ورود نمی‌سازد.",
                status = PeymanSetupStatus.BLOCKED, regime = regime, health = health, ensemble = ensemble,
            )
        }
        // مخالفت شمارش‌ها «وتوی سخت» نیست: طبق سند، باید امتیاز ساختار را پایین بیاورد یا
        // وضعیت را Watch کند. در `NeoWaveCountEnsemble` امتیاز ۸ واحد کم می‌شود و همین
        // معمولاً ستاپ را زیر آستانه می‌برد؛ پس لاگ می‌شود و در Trace می‌آید، ولی وتوی
        // مطلق نیست (اندازه‌گیری: وتوی مطلق ۴۵۹ تصمیم از ۱٬۳۸۲ را فقط به‌خاطر مخالفت
        // شمارش جایگزین حذف می‌کرد).
        val ensembleConflictNote = if (ensemble.agreement == NeoWaveEnsembleAgreement.CONFLICT) {
            "شمارش اصلی و جایگزین مخالف‌اند؛ امتیاز ساختار کاهش یافت."
        } else {
            ""
        }

        // ۴) انتخاب ستاپ: اگر کندل قبلی در فشردگی بوده، ستاپ «فشردگی → انبساط» است.
        val setup = if (priorCompression) {
            PeymanSetupType.COMPRESSION_EXPANSION
        } else {
            selectSetup(regime.regime, cfg)
        }
        if (setup == PeymanSetupType.NONE) {
            return reject(
                input,
                "برای رژیم «${regime.regime.labelFa}» ستاپ مجاز و فعالی وجود ندارد.",
                status = PeymanSetupStatus.BLOCKED, regime = regime, health = health, ensemble = ensemble,
            )
        }
        val isReversal = setup == PeymanSetupType.CAPITULATION_REVERSAL

        // ۵) BOS/بازآزمایی در جهت شمارش اصلی
        val minRvol = when (setup) {
            PeymanSetupType.CAPITULATION_REVERSAL -> cfg.minRvolReversal
            PeymanSetupType.COMPRESSION_EXPANSION -> max(cfg.minRvolContinuation, 1.5)
            else -> cfg.minRvolContinuation
        }
        val minBody = if (setup == PeymanSetupType.COMPRESSION_EXPANSION) {
            cfg.minCompressionBreakoutBodyRatio
        } else {
            cfg.minBodyRatio
        }
        val triggerClosed = PeymanMath.closed(input.triggerBars)
        val atr = PeymanMath.atr(triggerClosed, triggerClosed.size - 1)
        val noiseFloor = 0.05 * atr
        val strategyClosed = PeymanMath.closed(input.strategyBars)
        val strategyAtr = PeymanMath.atr(strategyClosed, strategyClosed.size - 1)
        val breakResult = CryptoStructuralBreakEngine.detect(
            bars = triggerClosed,
            symbol = symbol,
            rulesVersion = cfg.rulesVersion,
            intervalLabel = input.triggerInterval.label,
            direction = primary.direction,
            config = cfg,
            minRvol = minRvol,
            minBodyRatio = minBody,
            noiseFloorPrice = noiseFloor,
            referenceAtr = strategyAtr,
        )

        if (!breakResult.isEntryCandidate) {
            val status = when (breakResult.status) {
                PeymanSetupStatus.NONE -> PeymanSetupStatus.FORMING
                else -> breakResult.status
            }
            return reject(
                input,
                breakResult.bosNoteFa,
                setup = setup,
                status = status,
                regime = regime,
                health = health,
                ensemble = ensemble,
                breakResult = breakResult,
            )
        }

        // ۶) زمینهٔ BTC برای آلت‌ها
        val isAlt = !symbol.startsWith("BTC")
        val btcGate = BtcContextGate.evaluate(symbol, isAlt, input.btcContext, primary.direction, input.nowMillis)
        if (!btcGate.passed) {
            return reject(
                input, btcGate.reasonFa, setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
            )
        }

        // ۷) پلن: حد ضرر ساختاری ⇒ TradePlanGuard
        val lastBar = triggerClosed.last()
        val entry = lastBar.close
        val structural = breakResult.structuralLevel ?: return reject(
            input, "سطح ساختاری برای حد ضرر موجود نیست.", setup = setup, status = PeymanSetupStatus.BLOCKED,
            regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
        )
        val buffer = max(cfg.breakoutBufferAtr * atr, noiseFloor)
        val candidateStop = if (primary.direction == SignalAction.BUY) {
            val structuralStop = structural - buffer
            val barStop = if (breakResult.entryMode == PeymanEntryMode.RETEST_CONFIRMED) {
                minOf(lastBar.low, breakResult.breakoutLevel ?: structural) - 0.10 * atr
            } else {
                lastBar.low - 0.05 * atr
            }
            minOf(structuralStop, barStop)
        } else {
            val structuralStop = structural + buffer
            val barStop = if (breakResult.entryMode == PeymanEntryMode.RETEST_CONFIRMED) {
                maxOf(lastBar.high, breakResult.breakoutLevel ?: structural) + 0.10 * atr
            } else {
                lastBar.high + 0.05 * atr
            }
            maxOf(structuralStop, barStop)
        }
        val desiredRr = when (setup) {
            PeymanSetupType.COMPRESSION_EXPANSION -> 2.2
            PeymanSetupType.CAPITULATION_REVERSAL -> 1.8
            else -> 2.0
        }
        val risk = abs(entry - candidateStop)
        if (risk <= 0.0) {
            return reject(
                input, "فاصلهٔ حد ضرر صفر است؛ پلن نامعتبر.", setup = setup,
                status = PeymanSetupStatus.BLOCKED, regime = regime, health = health,
                ensemble = ensemble, breakResult = breakResult,
            )
        }
        // هدف: از دالان مجاز TradePlanGuard بالاترین R:R معقول انتخاب می‌شود، چون هزینه
        // روی R:R مؤثر اثر می‌گذارد. اگر اولین هدف پس از هزینه کافی نبود، پله بعدی امتحان
        // می‌شود (هیچ‌گاه فراتر از سقف دالان، و هیچ‌گاه با نزدیک‌کردن مصنوعی حد ضرر).
        val minRrNeeded = if (isReversal) cfg.minRiskRewardReversal else cfg.minRiskReward
        var plan: com.aurum.edge.core.GuardedTradePlan? = null
        var effectiveRr: Double? = null
        for (rrStep in listOf(desiredRr, desiredRr + 0.4, TradePlanGuard.MAX_RR)) {
            val target = if (primary.direction == SignalAction.BUY) entry + rrStep * risk else entry - rrStep * risk
            val attempt = TradePlanGuard.sanitizePlan(
                symbol = symbol,
                action = primary.direction,
                entryPrice = entry,
                candidateStopLoss = candidateStop,
                candidateTakeProfit = target,
                desiredRiskReward = rrStep,
                referenceAtr = atr,
                triggerInterval = input.triggerInterval,
                strategyAtr = atr,
                anchorEntryPrice = entry,
            )
            if (!attempt.isUsable) continue
            val cost = entry * (cfg.roundTurnFeePct + cfg.spreadPct) / 100.0 + cfg.slippageAtrFraction * atr
            val netRisk = kotlin.math.abs(attempt.entryPrice - attempt.stopLoss) + cost
            val netReward = kotlin.math.abs(attempt.takeProfit - attempt.entryPrice) - cost
            val rrEff = if (netRisk > 0.0) netReward / netRisk else 0.0
            plan = attempt
            effectiveRr = rrEff
            if (rrEff >= minRrNeeded) break
        }
        if (plan == null) {
            return reject(
                input,
                "TradePlanGuard هیچ پلن قابل‌اجرایی نداد.",
                setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
            )
        }
        val acceptedPlan = plan

        // ۸) کیفیت اجرا و هزینه
        val minRr = if (isReversal) cfg.minRiskRewardReversal else cfg.minRiskReward
        val quality = CryptoExecutionQualityGate.evaluate(
            symbol = symbol,
            direction = primary.direction,
            entry = acceptedPlan.entryPrice,
            stopLoss = acceptedPlan.stopLoss,
            takeProfit = acceptedPlan.takeProfit,
            atr = atr,
            triggerBars = triggerClosed,
            config = cfg,
            minRiskReward = minRr,
            requireVolume = true,
            obstacleBeforeIndex = breakResult.breakoutBarIndex,
        )
        if (!quality.ok) {
            return reject(
                input, quality.reasonsJoinedFa, setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
                effectiveRr = quality.effectiveRiskReward,
            )
        }

        // ۹) ریسک پرتفوی و خوشه
        val portfolio = CryptoPortfolioRiskGate.evaluate(
            direction = primary.direction,
            openPositions = input.openPositions,
            config = cfg,
            todayLossPct = input.todayLossPct,
            consecutiveLosses = input.consecutiveLosses,
            fakeBreakoutsSinceLastWin = input.fakeBreakoutsSinceLastWin,
            isReversal = isReversal,
        )
        if (!portfolio.passed) {
            return reject(
                input, portfolio.reasonFa, setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
                effectiveRr = quality.effectiveRiskReward,
            )
        }

        // ۱۰) ضدتکرار
        val setupId = breakResult.setupId
        if (setupId != null && input.alreadyEnteredSetupIds.contains(setupId)) {
            return reject(
                input, "این شکست قبلاً معامله شده است؛ یک ستاپ فقط یک ورود دارد.",
                setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
                effectiveRr = quality.effectiveRiskReward,
            )
        }

        // ۱۱) امتیاز کیفیت (۰..۱۰۰) — هیچ امتیازی وتوی سخت را خنثی نمی‌کند.
        val bosScore = bosQuality(breakResult, cfg)
        val volumeScore = volumeQuality(regime, breakResult)
        val mtfScore = mtfQuality(regime, btcGate)
        val execScore = execQuality(breakResult, quality, cfg)
        val qualityScore = (ensemble.scoreOutOf25 + bosScore + volumeScore + mtfScore + execScore).coerceIn(0.0, 100.0)

        val threshold = setup.minQualityScore
        if (qualityScore < threshold) {
            return reject(
                input,
                "امتیاز کیفیت ${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, qualityScore))} کمتر از آستانهٔ " +
                    "${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, threshold))} است.",
                setup = setup, status = PeymanSetupStatus.BLOCKED,
                regime = regime, health = health, ensemble = ensemble, breakResult = breakResult,
                effectiveRr = quality.effectiveRiskReward, qualityScore = qualityScore,
            )
        }

        val allowed = cfg.canTrade
        val decisionFa = when {
            !allowed -> "تصمیم معتبر است ولی حالت ${cfg.mode.labelFa} اجازهٔ معامله نمی‌دهد."
            breakResult.entryMode == PeymanEntryMode.RETEST_CONFIRMED -> "ورود روی بازآزمایی تأییدشده."
            else -> "ورود مستقیم روی شکست تأییدشده."
        }

        val trace = buildTrace(
            input = input,
            regime = regime,
            health = health,
            ensemble = ensemble,
            setup = setup,
            status = breakResult.status,
            breakResult = breakResult,
            quality = quality,
            qualityScore = qualityScore,
            btcGate = btcGate,
            failures = if (ensembleConflictNote.isBlank()) emptyList() else listOf(ensembleConflictNote),
            finalFa = if (ensembleConflictNote.isBlank()) decisionFa else "$decisionFa ($ensembleConflictNote)",
        )
        return PeymanCryptoDecision(
            action = if (allowed) primary.direction else SignalAction.NO_TRADE,
            readinessFa = decisionFa,
            setupType = setup,
            setupStatus = breakResult.status,
            entryMode = breakResult.entryMode,
            qualityScore = qualityScore,
            entry = acceptedPlan.entryPrice,
            stopLoss = acceptedPlan.stopLoss,
            takeProfit = acceptedPlan.takeProfit,
            riskRewardAfterCosts = quality.effectiveRiskReward,
            invalidationPrice = primary.invalidationPrice,
            setupId = setupId,
            mode = cfg.mode,
            isTradeAllowed = allowed,
            trace = trace,
        )
    }

    // ─────────────────────────── انتخاب و امتیازدهی ───────────────────────────

    fun selectSetup(regime: CryptoMarketRegime, config: PeymanCryptoConfig): PeymanSetupType = when (regime) {
        CryptoMarketRegime.COMPRESSION -> PeymanSetupType.COMPRESSION_EXPANSION
        CryptoMarketRegime.TREND_EXPANSION -> PeymanSetupType.WAVE3_CONTINUATION
        CryptoMarketRegime.CORRECTION -> PeymanSetupType.CORRECTION_COMPLETE
        CryptoMarketRegime.PANIC_CAPITULATION,
        CryptoMarketRegime.DISTRIBUTION,
        -> if (config.reversalEnabled) PeymanSetupType.CAPITULATION_REVERSAL else PeymanSetupType.NONE
        else -> PeymanSetupType.NONE
    }

    private fun bosQuality(result: StructuralBreakResult, cfg: PeymanCryptoConfig): Double {
        var score = 0.0
        if (result.isBosConfirmed) score += 12.0
        if (result.entryMode == PeymanEntryMode.RETEST_CONFIRMED) score += 6.0
        // کیفیت کندل شکست: بدنه، CLV و انبساط واقعی نوسان (نه فقط وجود سطح).
        score += (result.bodyRatio.coerceIn(0.0, 1.0)) * 5.0
        val clvGood = if (result.direction == SignalAction.BUY) {
            (result.clv - cfg.minClvLong).coerceAtLeast(0.0)
        } else {
            (cfg.maxClvShort - result.clv).coerceAtLeast(0.0)
        }
        score += (clvGood * 10.0).coerceIn(0.0, 3.0)
        score += when {
            result.atrExpansionRatio >= 1.30 -> 4.0
            result.atrExpansionRatio >= cfg.minAtrExpansionRatio -> 2.5
            else -> 0.0
        }
        return score.coerceIn(0.0, 25.0)
    }

    private fun volumeQuality(regime: CryptoRegimeSnapshot, result: StructuralBreakResult): Double {
        val rvol = result.relativeVolume ?: return 6.0
        var score = (rvol / 3.0 * 14.0).coerceIn(0.0, 14.0)
        if (regime.shockBar) score *= 0.5
        if (result.atrExpansionRatio >= 1.30) score += 6.0
        else if (result.atrExpansionRatio >= 1.15) score += 4.0
        return score.coerceIn(0.0, 20.0)
    }

    private fun mtfQuality(regime: CryptoRegimeSnapshot, btcGate: PeymanGateResult): Double {
        val regimePart = when (regime.regime) {
            CryptoMarketRegime.COMPRESSION -> 10.0
            CryptoMarketRegime.TREND_EXPANSION -> 10.0
            CryptoMarketRegime.CORRECTION -> 7.0
            CryptoMarketRegime.DISTRIBUTION -> 5.0
            CryptoMarketRegime.PANIC_CAPITULATION -> 4.0
            else -> 2.0
        }
        val btcPart = if (btcGate.passed) 5.0 else 0.0
        return (regimePart + btcPart).coerceIn(0.0, 15.0)
    }

    private fun execQuality(result: StructuralBreakResult, quality: ExecutionQuality, cfg: PeymanCryptoConfig): Double {
        var score = 0.0
        val distance = result.distanceFromLevelAtr ?: 1.0
        score += when {
            distance <= cfg.maxDirectDistanceAtr -> 6.0
            distance <= cfg.maxDirectDistanceAtr * 2 -> 3.0
            else -> 0.0
        }
        score += when {
            quality.effectiveRiskReward >= 2.2 -> 6.0
            quality.effectiveRiskReward >= 1.8 -> 4.0
            else -> 2.0
        }
        if (quality.obstacleSpaceR == null || quality.obstacleSpaceR >= cfg.minObstacleSpaceR * 1.5) score += 3.0
        return score.coerceIn(0.0, 15.0)
    }

    // ─────────────────────────── رد و Trace ───────────────────────────

    private fun reject(
        input: PeymanCryptoInput,
        reasonFa: String,
        setup: PeymanSetupType = PeymanSetupType.NONE,
        status: PeymanSetupStatus,
        regime: CryptoRegimeSnapshot? = null,
        health: PeymanDataHealth? = null,
        ensemble: NeoWaveCountEnsembleResult? = null,
        breakResult: StructuralBreakResult? = null,
        effectiveRr: Double? = null,
        qualityScore: Double = 0.0,
    ): PeymanCryptoDecision {
        val btcGate = PeymanGateResult.ok()
        val trace = buildTrace(
            input = input,
            regime = regime ?: CryptoRegimeSnapshot(
                CryptoMarketRegime.UNKNOWN, 50.0, 0.0, 0.0, 1.0, null, false, emptyList(),
            ),
            health = health ?: PeymanDataHealth.broken(reasonFa),
            ensemble = ensemble ?: NeoWaveCountEnsembleResult(
                null, emptyList(), false, NeoWaveEnsembleAgreement.NONE, 0.0, "",
            ),
            setup = setup,
            status = status,
            breakResult = breakResult,
            quality = null,
            qualityScore = qualityScore,
            btcGate = btcGate,
            failures = listOf(reasonFa),
            finalFa = reasonFa,
            effectiveRr = effectiveRr,
        )
        val mode = input.config.mode
        return PeymanCryptoDecision(
            action = SignalAction.NO_TRADE,
            readinessFa = reasonFa,
            setupType = setup,
            setupStatus = status,
            entryMode = breakResult?.entryMode ?: PeymanEntryMode.NONE,
            qualityScore = qualityScore,
            entry = null,
            stopLoss = null,
            takeProfit = null,
            riskRewardAfterCosts = effectiveRr,
            invalidationPrice = ensemble?.primary?.invalidationPrice,
            setupId = breakResult?.setupId,
            mode = mode,
            isTradeAllowed = false,
            trace = trace,
        )
    }

    private fun buildTrace(
        input: PeymanCryptoInput,
        regime: CryptoRegimeSnapshot,
        health: PeymanDataHealth,
        ensemble: NeoWaveCountEnsembleResult,
        setup: PeymanSetupType,
        status: PeymanSetupStatus,
        breakResult: StructuralBreakResult?,
        quality: ExecutionQuality?,
        qualityScore: Double,
        btcGate: PeymanGateResult,
        failures: List<String>,
        finalFa: String,
        effectiveRr: Double? = null,
    ): PeymanDecisionTrace {
        val primary = ensemble.primary
        return PeymanDecisionTrace(
            rulesVersion = input.config.rulesVersion,
            evaluatedAt = input.nowMillis,
            mode = input.config.mode,
            regime = regime.regime,
            regimeFa = "رژیم: ${regime.regime.labelFa} (${regime.reasonsJoinedFa})",
            dataHealthFa = if (health.isHealthy) {
                "داده سالم · ماشه ${PeymanMath.faDigits(health.triggerBars.toString())} کندل"
            } else {
                health.reasonsFa.joinToString(" ")
            },
            primaryCountFa = primary?.let {
                "شمارش اصلی: ${it.noteFa} (${PeymanMath.faDigits(it.rulesPassed.toString())}/${PeymanMath.faDigits(it.rulesTotal.toString())} قاعده)"
            } ?: "شمارش اصلی: ندارد",
            alternativeCountFa = if (ensemble.alternatives.isEmpty()) {
                "شمارش جایگزین: ندارد"
            } else {
                "شمارش جایگزین: " + ensemble.alternatives.joinToString("، ") { it.id }
            },
            ensembleAgreement = ensemble.agreement,
            invalidationPrice = primary?.invalidationPrice,
            setupType = setup,
            setupStatus = status,
            bosStatusFa = breakResult?.bosNoteFa ?: "BOS ارزیابی نشد",
            relativeVolume = breakResult?.relativeVolume ?: regime.relativeVolume,
            atrPercentile = regime.atrPercentile,
            btcContextFa = BtcContextGate.describe(input.btcContext) +
                (if (btcGate.passed) "" else " · ${btcGate.reasonFa}"),
            distanceFromBreakoutAtr = breakResult?.distanceFromLevelAtr,
            qualityScore = qualityScore,
            effectiveRiskReward = effectiveRr ?: quality?.effectiveRiskReward,
            hardGateFailures = failures,
            finalDecisionFa = finalFa,
            setupId = breakResult?.setupId,
        )
    }
}
