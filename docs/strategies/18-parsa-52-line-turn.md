# ۱۸ · استراتژی پارسا · چرخش ابر پس از فلت ۵۲
`PARSA_52_LINE_TURN` · پارسا · فلت >۱۷ + چرخش + کراس

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `PARSA_52_LINE_TURN` |
| نشان | پارسا · فلت >۱۷ + چرخش + کراس |
| مکتب‌ها | — (روش مستقل؛ شرط‌های خودش را دارد) |
| تعداد مکتب | — (روش مستقل) |
| R:R پایه | ۲.۱۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | روش مستقل پارسا · ایچیموکو ۹/۲۶/۵۲ |
| انتخاب صریح کاربر | بله — پیش‌فرض خاموش |
| یادگیری عصبی | مستثنا (بدون آموزش/گیت) |

## خلاصه
«خط ۵۲» در این روش صریحاً Senkou Span B (SSB، خط دورهٔ ۵۲) است. فقط پس از تخت‌ماندن SSB دست‌کم ۱۸ کندل بسته، چرخش Senkou Span A و کراس هم‌جهت تنکان/کیجون بررسی می‌شوند؛ سپس شیب و فاصلهٔ خطوط، آزادی Chikou، کیفیت شکست و نشانه‌های حرکت شارپ فیلتر می‌شوند. روش به یادگیری و تیونینگ عصبی وصل نیست.

سه شرط هستهٔ سخت + پنج شرط کیفیت؛ اگر یکی از سه شرط هسته نباشد، ورود نیست.

## شرط‌ها
موتور اختصاصی: `core/ParsaLineTurnEngine.kt`

| # | شرط |
|---|---|
| ۱ | فلت واقعی Senkou Span B (خط دورهٔ ۵۲) |
| ۲ | چرخش Senkou Span A برای ابر آینده |
| ۳ | کراس هم‌جهت Tenkan/Kijun در ۸ کندل |
| ۴ | شیب همسو و نبود مخالفت شدید Kijun |
| ۵ | فاصله و گسترش سنجیدهٔ Tenkan/Kijun |
| ۶ | آزادی Chikou |
| ۷ | کیفیت حرکت شارپ و تأیید حجم/ATR |
| ۸ | سمت قیمت و نبود تضاد تایم‌فریم بالاتر |

| ثابت | مقدار |
|---|---|
| `MIN_FLAT_BARS` | `18` |
| `MAX_CROSS_AGE_BARS` | `8` |
| `MIN_SPAN_A_TURN_ATR` | `0.025` |
| `MIN_QUALITY_SCORE` | `3` |


## کد
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
**پارسا: سه‌لایه به‌جای «نُه شرط AND»** — خطوط ۳۸۰۳ تا ۳۸۴۲

```kotlin
        // ── پارسا: سه‌لایه به‌جای «نُه شرط AND» ─────────────────────────────────
        // اندازه‌گیری پیش از این تغییر: ۰ معامله در سه سری ۲۵۰۰ کندلی و هیچ‌وقت
        // بیش از ۸ شرط از ۹. حاصل‌ضرب نُه احتمال یعنی عملاً هیچ ستاپی کامل نمی‌شود.
        // اکنون: هویت روش (فلت SSB + چرخش Span A + کراس تازه) وتوی سخت است و
        // پنج تأیید کیفیتی با امتیاز سنجیده می‌شوند — همان الگوی لایه‌بندی موتور.
        val parsaSpec = ParsaLineTurnEngine.spec(
            flatBars = trigSnap.senkouSpanBFlatBars,
            spanAPrevSlopeAtr = trigSnap.previousSenkouSpanASlopeAtrPerBar,
            spanASlopeAtr = trigSnap.senkouSpanASlopeAtrPerBar,
            crossAgeBars = parsaCrossAge,
            slopeOk = parsaSlopeOk,
            lineSpacingOk = parsaLineSpacingOk,
            chikouOpen = parsaChikouOk,
            cloudSideOk = parsaCloudOk,
            sharpMoveOk = parsaSharpMoveOk,
            isLong = isDirectionalLong,
            slopeDetailFa = String.format(
                Locale.US,
                "شیب Tenkan=%+.2f · Kijun=%+.2f · Senkou A=%+.2f ATR/کندل",
                trigSnap.tenkanSlopeAtrPerBar,
                trigSnap.kijunSlopeAtrPerBar,
                trigSnap.senkouSpanASlopeAtrPerBar,
            ),
            spacingDetailFa = String.format(
                Locale.US,
                "فاصله=%.2f ATR · تغییر فاصله=%+.2f ATR · قیمت تا Kijun=%.2f ATR",
                trigSnap.tenkanKijunGapAtr,
                trigSnap.tenkanKijunGapExpansionAtr,
                trigSnap.distanceToKijunAtr,
            ),
            sharpDetailFa = String.format(
                Locale.US,
                "بدنه=%.0f%% · حجم=%.2fx · گسترش ATR=%.2fx",
                trigSnap.candleBodyRatio * 100.0,
                trigSnap.volumeRatio20,
                trigSnap.atrExpansionRatio52,
            ),
        )
        val parsaReady = parsaSpec.passed
```
**Compute Sniper Entry Zone & Tight Structural SL (متود یکپارچه با جامع بیس)** — خطوط ۳۹۴۶ تا ۴۰۴۲
<details><summary>کد کامل (۹۶ خط)</summary>

```kotlin
        // ── Compute Sniper Entry Zone & Tight Structural SL (متود یکپارچه با جامع بیس) ──
        val slBuffer = trigSnap.atr14 * effectiveConfig.slBufferAtr
        val candleGoldenPocket = if (isLong) {
            trigSnap.low + (trigSnap.high - trigSnap.low) * 0.55
        } else {
            trigSnap.high - (trigSnap.high - trigSnap.low) * 0.55
        }
        val activeTenkanRef = trigSnap.tenkan
        val sniperOptimal = if (isLong) {
            round2(max(activeTenkanRef, min(livePrice, candleGoldenPocket)))
        } else {
            round2(min(activeTenkanRef, max(livePrice, candleGoldenPocket)))
        }
        val sniperLow = if (isLong) {
            round2(min(activeTenkanRef, sniperOptimal) - trigSnap.atr14 * 0.12)
        } else {
            round2(min(sniperOptimal, livePrice) - trigSnap.atr14 * 0.35)
        }
        val sniperHigh = if (isLong) {
            round2(max(sniperOptimal, livePrice) + trigSnap.atr14 * 0.35)
        } else {
            round2(max(activeTenkanRef, sniperOptimal) + trigSnap.atr14 * 0.12)
        }
        val distToSniperAtr = abs(livePrice - sniperOptimal) / trigSnap.atr14
        val sniperEntryScore = round2((100.0 - distToSniperAtr * 55.0).coerceIn(15.0, 100.0))

        val wave = computeHosodaWave(alignedStrategyCandles, candidateDirection, stratSnap)
        val minSafeStopDist = minSafeStructuralStopDistance(
            symbol = normalizedSymbol,
            referencePrice = livePrice,
            trigAtr14 = trigSnap.atr14,
            stratAtr14 = stratSnap.atr14,
            triggerInterval = effectiveConfig.triggerInterval,
        )
        val safeAnchorLong = min(sniperOptimal, livePrice)
        val safeAnchorShort = max(sniperOptimal, livePrice)
        val referenceEntryForSl = if (isLong) safeAnchorLong else safeAnchorShort

        // متود روز دنیا برای حد ضرر: هم‌پوشانی ۵ سنگر ابطال ساختاری (فرکتال ۵ کندلی بیل ویلیامز، کیجون۲۶، کیجون۳۰/EMA34، لبه کومو و نقطه S هوسودا)
        // به‌علاوه بافر تطبیقی ضد استاپ‌هانت و محصور در دالان طلایی نوسان [minSafeStopDist .. 2.10 * ATR14]
        val baseTechnicalSl = computeOptimalStructuralStopLoss(
            symbol = normalizedSymbol,
            isLong = isLong,
            referenceEntry = referenceEntryForSl,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            wave = wave,
            slBufferAtr = effectiveConfig.slBufferAtr,
            triggerInterval = effectiveConfig.triggerInterval,
            activeModel = activeModel,
        )
        val specialStopAnchor = when {
            isBtc52Model && isLong -> trigSnap.prior52Low - trigSnap.atr14 * effectiveConfig.slBufferAtr
            isBtc52Model -> trigSnap.prior52High + trigSnap.atr14 * effectiveConfig.slBufferAtr
            // هیدن کراس: ۱R پشت خودِ نوار ابر مخفی قفل می‌شود؛ اگر به ورود چسبیده باشد،
            // سنگر ساختاری ۵ کندلی جای آن را می‌گیرد (بدون تورم مصنوعی ۱R).
            isHiddenCrossModel && isLong -> min(
                trigSnap.hiddenCloudBottom - trigSnap.atr14 * effectiveConfig.slBufferAtr,
                trigSnap.low - trigSnap.atr14 * effectiveConfig.slBufferAtr,
            )
            isHiddenCrossModel -> max(
                trigSnap.hiddenCloudTop + trigSnap.atr14 * effectiveConfig.slBufferAtr,
                trigSnap.high + trigSnap.atr14 * effectiveConfig.slBufferAtr,
            )
            isParsaModel && isLong && trigSnap.currentSpanB < referenceEntryForSl ->
                trigSnap.currentSpanB - trigSnap.atr14 * effectiveConfig.slBufferAtr
            isParsaModel && !isLong && trigSnap.currentSpanB > referenceEntryForSl ->
                trigSnap.currentSpanB + trigSnap.atr14 * effectiveConfig.slBufferAtr
            else -> null
        }
        val rawTechnicalSl = specialStopAnchor?.let { structural ->
            if (isLong) min(baseTechnicalSl, structural) else max(baseTechnicalSl, structural)
        } ?: baseTechnicalSl

        val rawStopDist = max(
            abs(sniperOptimal - rawTechnicalSl),
            abs(livePrice - rawTechnicalSl),
        ).coerceAtLeast(minSafeStopDist)

        // حد ضرر ساختاری (Kijun / Senkou B / 5-Bar Fractal + ATR Buffer) کاملاً ثابت و غیرپله‌ای است
        // قاعدهٔ تازهٔ استراتژی ۱۶: حد ضرر بالای خط ۲۶ (برای خرید) و با فاصله از کندل؛ اگر جمع نشود، پلن مسدود می‌شود.
        val kijunStopRule = if (KIJUN_LAYER_IN_STRATEGY_16_ENABLED && activeModel.isNeoWaveElliottStrategy) {
            applyKijunStopRules(
                isLong = isLong,
                candidateStop = rawTechnicalSl,
                triggerSnapshot = trigSnap,
            )
        } else {
            null
        }
        val technicalSl = kijunStopRule?.stop ?: rawTechnicalSl

        val stopDist = max(
            abs(sniperOptimal - technicalSl),
            abs(livePrice - technicalSl),
        ).coerceAtLeast(minSafeStopDist)
```

</details>

**محاسبه «عدد مناسب R:R» (Optimal R:R) در بازه قفل‌شده [1:1.50 .. 1:3.00] برای تمام ۱۶ استراتژی** — خطوط ۴۱۰۱ تا ۴۱۸۹
<details><summary>کد کامل (۸۸ خط)</summary>

```kotlin
        // ── محاسبه «عدد مناسب R:R» (Optimal R:R) در بازه قفل‌شده [1:1.50 .. 1:3.00] برای تمام ۱۶ استراتژی ──
        val modelBaseOptimalRr = activeModel.baseOptimalRr.coerceIn(1.50, 3.00)
        val phaseRrBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 0.25
            TrendPhase.CORE_EXPANSION -> 0.12
            else -> 0.0
        }
        val erRrBonus = ((erStrat + erTrig - 0.50) * 0.45).coerceIn(-0.15, 0.35)
        val volRrBonus = if (trigSnap.volumeRatio20 >= 1.20) 0.12 else 0.0
        val computedOptimalRr = round2(
            (modelBaseOptimalRr + phaseRrBonus + erRrBonus + volRrBonus).coerceIn(requiredMinRr, maxAllowedRr)
        )

        val labeledWaveTargets: List<Pair<String, Double>> = if (wave != null) {
            listOf(
                "موج NT هوسودا" to wave.targetNT,
                "موج V هوسودا" to wave.targetV,
                "موج N هوسودا" to wave.targetN,
                "موج E هوسودا" to wave.targetE,
            ).sortedBy { abs(it.second - sniperOptimal) }
        } else {
            emptyList()
        }

        // انتخاب هدف قیمتی از بین امواج هوسودا (NT, V, N, E) که داخل دالان [requiredMinRr .. 3.00] قرار داشته باشد
        val validWaveCandidates = labeledWaveTargets.filter { (_, targetPrice) ->
            val inRightDirection = if (isLong) targetPrice > max(livePrice, sniperOptimal) else targetPrice < min(livePrice, sniperOptimal)
            val minDistFromEntries = min(abs(targetPrice - livePrice), abs(targetPrice - sniperOptimal))
            val maxDistFromEntries = max(abs(targetPrice - livePrice), abs(targetPrice - sniperOptimal))
            inRightDirection && (minDistFromEntries / stopDist) >= requiredMinRr && (maxDistFromEntries / stopDist) <= maxAllowedRr
        }
        val chosenWavePair: Pair<String, Double>? = validWaveCandidates.minByOrNull { (_, targetPrice) ->
            val targetRr = abs(targetPrice - sniperOptimal) / stopDist
            abs(targetRr - computedOptimalRr)
        }

        val entryAnchorForTp = if (isLong) max(livePrice, sniperOptimal) else min(livePrice, sniperOptimal)
        val minTpDist = stopDist * requiredMinRr
        val maxTpDist = stopDist * maxAllowedRr
        val optTpDist = stopDist * computedOptimalRr.coerceIn(requiredMinRr, maxAllowedRr)

        val btc52TargetDistance = (trigSnap.range52Atr * trigSnap.atr14 * 0.35)
            .coerceIn(minTpDist, maxTpDist)
        // هیدن کراس: هدف اول «تاج» است؛ اگر تاج در دالان [۱٫۵R..۳R] نبود، سطح برابری و
        // در نهایت هدف توزیع‌شدهٔ بهینه به‌کار می‌رود — همیشه داخل همان دالان قفل‌شده.
        val hiddenCrossCrownPrice = when {
            trigSnap.crownLevel <= 0.0 -> 0.0
            isLong && trigSnap.crownLevel <= entryAnchorForTp -> 0.0
            !isLong && trigSnap.crownLevel >= entryAnchorForTp -> 0.0
            else -> trigSnap.crownLevel
        }
        val hiddenCrossCrownInCorridor = hiddenCrossCrownPrice > 0.0 &&
            (abs(hiddenCrossCrownPrice - entryAnchorForTp) / stopDist) in requiredMinRr..maxAllowedRr
        val rawPrimaryTp = when {
            isBtc52Model && isLong -> entryAnchorForTp + btc52TargetDistance
            isBtc52Model -> (entryAnchorForTp - btc52TargetDistance).coerceAtLeast(0.00001)
            isHiddenCrossModel && hiddenCrossCrownInCorridor -> hiddenCrossCrownPrice
            isParsaModel && isLong -> entryAnchorForTp + optTpDist
            isParsaModel -> (entryAnchorForTp - optTpDist).coerceAtLeast(0.00001)
            chosenWavePair != null -> chosenWavePair.second
            isLong -> entryAnchorForTp + optTpDist
            else -> (entryAnchorForTp - optTpDist).coerceAtLeast(0.00001)
        }
        val selectedWaveLabel = when {
            isBtc52Model -> "امتداد دامنهٔ ساختار ۵۲ کندلی"
            isHiddenCrossModel && hiddenCrossCrownInCorridor -> "تاج کراس برابری (نصف اندازهٔ کراس)"
            isHiddenCrossModel -> "هدف بهینه پس از کراس برابری"
            isParsaModel -> "هدف حرکتی پس از چرخش Senkou A"
            chosenWavePair != null -> chosenWavePair.first
            else -> String.format(Locale.US, "موج بهینه هوسودا (%.2fR)", computedOptimalRr)
        }

        // Check nearest HTF horizontal plateau (Karen Péloille's Flat SSB/Kijun wall)
        // لایهٔ «هیدن کراس» به‌صورت پیش‌فرض وارد استراتژی پایه هم شده است: سطح تاجی که از
        // کراس برابری تنکان/کیجون ساخته می‌شود، یک دیوار افقی واقعی است و باید مثل پلاتوی
        // فلت پلویل دیده شود. اگر تاجی در مسیر نباشد، رفتار دقیقاً مثل قبل می‌ماند.
        val hiddenCrossWall = if (effectiveConfig.includeHiddenCrossLayer && trigSnap.nearestCrownLevel > 0.0) {
            listOf(trigSnap.nearestCrownLevel)
        } else {
            emptyList()
        }
        val allPlateaus = ((macroSnap?.flatEquilibriumLevels ?: emptyList()) +
            stratSnap.flatEquilibriumLevels + hiddenCrossWall).distinct().sorted()
        val nearestObstacle = if (isLong) {
            allPlateaus.firstOrNull { it > livePrice + trigSnap.atr14 * 0.4 }
        } else {
            allPlateaus.lastOrNull { it < livePrice - trigSnap.atr14 * 0.4 }
        }
```

</details>

**موتور واحد ایمن‌سازی پلن (Trade Plan Guard)** — خطوط ۴۲۲۹ تا ۴۳۲۲
<details><summary>کد کامل (۹۳ خط)</summary>

```kotlin
        // ── موتور واحد ایمن‌سازی پلن (Trade Plan Guard) ─────────────────────────
        // حد ضرر ساختاری و هدف موج هوسودا داخل «دالان نوسان تایم‌فریم» قفل می‌شوند؛ بنابراین
        // ۱R هرگز از نوسان واقعی همان تایم‌فریم بزرگ‌تر نمی‌شود و هدف هم روی کاغذ باقی نمی‌ماند.
        val guardedPlan = TradePlanGuard.sanitizePlan(
            symbol = normalizedSymbol,
            action = if (isLong) SignalAction.BUY else SignalAction.SELL,
            entryPrice = sniperOptimal,
            candidateStopLoss = technicalSl,
            candidateTakeProfit = effectiveTp,
            desiredRiskReward = computedOptimalRr,
            referenceAtr = trigSnap.atr14,
            triggerInterval = effectiveConfig.triggerInterval,
            strategyAtr = stratSnap.atr14,
        )
        // در گزارش‌های بدون سیگنال (NO_TRADE) اعداد ساختاری خام برای نمایش حفظ می‌شوند؛
        // اما هر گزارش قابل‌اجرا فقط با پلن ایمن‌شده ساخته می‌شود.
        val finalStopLoss = if (guardedPlan.isUsable) guardedPlan.stopLoss else round2(technicalSl)
        val finalTakeProfit = if (guardedPlan.isUsable) guardedPlan.takeProfit else round2(effectiveTp)
        val finalStopDist = if (guardedPlan.isUsable) {
            guardedPlan.stopDistance.coerceAtLeast(trigSnap.atr14 * 0.25)
        } else {
            abs(sniperOptimal - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.25)
        }
        val finalTargetDist = if (guardedPlan.isUsable) guardedPlan.targetDistance else abs(effectiveTp - sniperOptimal)

        val rr = if (guardedPlan.isUsable) {
            round2(guardedPlan.riskReward.coerceIn(1.50, 3.00))
        } else {
            round2(computedOptimalRr.coerceIn(1.50, 3.00))
        }
        val effectiveOptimalRr = round2(max(computedOptimalRr, rr).coerceIn(1.50, 3.00))

        val obstacleClear = if (nearestObstacle == null) {
            true
        } else {
            (min(abs(nearestObstacle - livePrice), abs(nearestObstacle - sniperOptimal)) / finalStopDist) >= requiredMinRr
        }
        val cond12Passed = rr in requiredMinRr..maxAllowedRr && obstacleClear

        val rewardDistSniper = round2(finalTargetDist)
        val riskDistSniper = round2(finalStopDist)
        val slBasisTextFa = buildString {
            append("هم‌پوشانی فرکتال ۵ کندلی بیل ویلیامز + کیجون۲۶ (${round2(trigSnap.kijun)}) + کیجون۳۰/EMA34 + لبه کومو")
            if (activeModel == BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER) {
                append(" + هندسهٔ الیوت–نئوویو (نسخهٔ پیمان، هم‌تراز جامع بیس)")
            }
            append(String.format(Locale.US, " + سپر ضد شدو (%.2f ATR) در دالان [0.80..2.10 ATR]", slBuffer))
            append(" + قفل سقف درصدی نوسان " + effectiveConfig.triggerInterval.label)
        }
        val tpBasisTextFa = buildString {
            append(selectedWaveLabel)
            append(" (${round2(finalTakeProfit)}) · قفل در بازه [1:1.50 .. 1:3.00]")
            if (nearestObstacle != null) {
                append(" · قبل از دیوار فلت تایم بالاتر ($nearestObstacle)")
            }
        }
        val formulaTextFa = String.format(
            Locale.US,
            "فرمول: پاداش (|TP−Entry| = %s) ÷ ریسک ۱R (|Entry−SL| = %s) = 1:%.2f (در بازه مجاز ۱.۵ تا ۳.۰)",
            rewardDistSniper.toString(),
            riskDistSniper.toString(),
            rr,
        )
        val optimalRrReasonTextFa = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f", rr))
            append(" (در بازه قفل‌شده [1:1.50 .. 1:3.00] · پایه ${activeModel.badgeFa}: 1:${String.format(Locale.US, "%.2f", modelBaseOptimalRr)}")
            append(" + ${trendPhase.badgeFa}")
            append(" + کارایی ER=${(erStrat * 100).toInt()}%")
            append(" ← هدف: $selectedWaveLabel · 🔒 حد ضرر ساختاری کاملاً ثابت و غیرپله‌ای)")
            append(" · 🛡️ ${guardedPlan.summaryFa()}")
        }
        val rrBreakdownObj = RrCalculationBreakdown(
            entryPrice = round2(sniperOptimal),
            stopLossPrice = round2(finalStopLoss),
            takeProfitPrice = round2(finalTakeProfit),
            riskDistance1R = riskDistSniper,
            rewardDistance = rewardDistSniper,
            actualRiskReward = rr,
            optimalRiskReward = effectiveOptimalRr,
            selectedTargetWaveLabel = selectedWaveLabel,
            formulaTextFa = formulaTextFa,
            slBasisFa = slBasisTextFa,
            tpBasisFa = tpBasisTextFa,
            optimalRrReasonFa = optimalRrReasonTextFa,
            runnerArmedAbove3R = false,
        )

        val cond12Detail = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f (%s · بازه مجاز 1.5..3.0)", rr, selectedWaveLabel))
            append(" · فرمول: $rewardDistSniper ÷ $riskDistSniper")
            append(" · 🔒 حد ضرر ثابت ساختاری")
            append(" · 🛡️ ${guardedPlan.verdict.badgeFa}")
        }
```

</details>

### کد کامل این استراتژی (پوشهٔ جداگانه)

| فایل | خط | داخل این سند |
|---|---|---|
| `code/18-parsa-52-line-turn/ParsaLineTurnEngine.kt` | ۲۰۲ | کامل، پایین |

**core/ParsaLineTurnEngine.kt**
<details><summary>کد کامل (۲۰۲ خط)</summary>

```kotlin
package com.aurum.edge.core

import java.util.Locale

/**
 * # استراتژی پارسا — «چرخش خط ۵۲ (Senkou Span B) و چرخش Senkou Span A»
 *
 * ## چه چیزی خراب بود
 * هر نُه شرط با `AND` خالص روی هم سوار شده بودند. هر شرط به‌تنهایی معقول است، اما
 * حاصل‌ضرب نُه احتمال (حتی اگر هر کدام ۶۰٪ باشد) می‌شود ۱٪ — و اندازه‌گیری روی سه سری
 * ۲۵۰۰ کندلی این را نشان داد: **۰ معامله، و هیچ‌وقت بیش از ۸ شرط از ۹ شرط**.
 * یعنی استراتژی به‌طور ساختاری مرده بود، نه اینکه بازار ستاپ نداده باشد.
 *
 * ## چه چیزی جایش آمد
 * همان سه‌لایه‌ای که خودِ موتور برای روش بیس مستند کرده است (رجوع به بلوک «دروازه‌بندی
 * مکتبی بیس» در `IchimokuStrategyEngine`):
 *
 * | لایه | شرط‌ها | قاعده |
 * |---|---|---|
 * | ۱ — هویت روش (وتوی سخت) | فلت ۱۸+ کندلی SSB · چرخش Span A · کراس TK در ۸ کندل | **همه** باید باشند |
 * | ۲ — کیفیت و تأیید | شیب · فاصلهٔ TK · آزادی چیکو · سمت ابر · حرکت شارپ | دست‌کم [MIN_QUALITY_SCORE] از ۵ |
 *
 * چرا این «شل‌کردن» نیست: هویت روش دست‌نخورده است. اگر SSB فلت نباشد، یا Span A
 * نچرخد، یا کراس تازه نباشد، هیچ معامله‌ای باز نمی‌شود — همان چیزی که نام روش می‌گوید.
 * آنچه حذف شد، «هم‌زمانیِ کاملِ پنج تأیید کیفیتی» بود که در هیچ منبعی شرط ورود نیست.
 */
object ParsaLineTurnEngine {

    /** کمینهٔ کندل‌های فلت Senkou Span B (خط دورهٔ ۵۲) پیش از چرخش. */
    const val MIN_FLAT_BARS: Int = 18

    /** مهلت مجاز پس از کراس Tenkan/Kijun. */
    const val MAX_CROSS_AGE_BARS: Int = 8

    /** کمینهٔ شیب Span A برای «چرخش» (بر حسب ATR در هر کندل). */
    const val MIN_SPAN_A_TURN_ATR: Double = 0.025

    /** از پنج تأیید کیفیتی، دست‌کم چند‌تا لازم است. */
    const val MIN_QUALITY_SCORE: Int = 3

    /** یک شرط قابل‌نمایش در گزارش استراتژی. */
    data class Rule(
        val index: Int,
        val titleFa: String,
        val passed: Boolean,
        val detailFa: String,
        /** آیا این شرط از لایهٔ ۱ (هویت روش) است؟ */
        val isCore: Boolean,
    )

    /**
     * نتیجهٔ سنجش کامل روش.
     *
     * @property rules هشت شرط روش (شرط نهم = پلن/R:R را موتور اضافه می‌کند).
     * @property corePassed هر سه شرط هویتی پاس شده‌اند.
     * @property qualityScore تعداد تأییدهای کیفیتی پاس‌شده (۰..۵).
     * @property passed آیا روش اجازهٔ ورود می‌دهد.
     */
    data class Spec(
        val rules: List<Rule>,
        val corePassed: Boolean,
        val qualityScore: Int,
        val passed: Boolean,
    ) {
        val coreRules: List<Rule> get() = rules.filter { it.isCore }
        val failedCoreFa: String
            get() = coreRules.filterNot { it.passed }.joinToString(" · ") { it.titleFa }
        val summaryFa: String
            get() = if (passed) {
                String.format(
                    Locale.US,
                    "✅ روش پارسا کامل است: هویت روش تأیید شد و %d از ۵ تأیید کیفیتی رسید.",
                    qualityScore,
                )
            } else if (!corePassed) {
                "⛔ هویت روش کامل نیست: $failedCoreFa"
            } else {
                String.format(
                    Locale.US,
                    "⏸ تأییدهای کیفیتی کافی نیست: %d از ۵ (حد لازم %d).",
                    qualityScore,
                    MIN_QUALITY_SCORE,
                )
            }
    }

    /** ارقام لاتین → فارسی. متن‌های برنامه فارسی‌اند و «18» در آن‌ها ناهمخوان دیده می‌شود. */
    internal fun faDigits(value: Int): String =
        value.toString().map { ch -> if (ch in '0'..'9') '۰' + (ch - '0') else ch }.joinToString("")

    /**
     * سنجش روش پارسا.
     *
     * همهٔ ورودی‌ها از `IchimokuBarSnapshot` آمده‌اند؛ این تابع خودش هیچ محاسبهٔ ایچیموکویی
     * انجام نمی‌دهد تا در آزمون بتوان هر شرط را تک‌تک روشن/خاموش کرد.
     */
    @Suppress("LongParameterList")
    fun spec(
        flatBars: Int,
        spanAPrevSlopeAtr: Double,
        spanASlopeAtr: Double,
        crossAgeBars: Int,
        slopeOk: Boolean,
        lineSpacingOk: Boolean,
        chikouOpen: Boolean,
        cloudSideOk: Boolean,
        sharpMoveOk: Boolean,
        isLong: Boolean,
        slopeDetailFa: String = "",
        spacingDetailFa: String = "",
        sharpDetailFa: String = "",
    ): Spec {
        val flatOk = flatBars >= MIN_FLAT_BARS
        val spanATurnOk = if (isLong) {
            spanAPrevSlopeAtr <= 0.0 && spanASlopeAtr >= MIN_SPAN_A_TURN_ATR
        } else {
            spanAPrevSlopeAtr >= 0.0 && spanASlopeAtr <= -MIN_SPAN_A_TURN_ATR
        }
        val crossOk = crossAgeBars <= MAX_CROSS_AGE_BARS

        val rules = listOf(
            Rule(
                1,
                "۱. فلت واقعی Senkou Span B (خط دورهٔ ۵۲)",
                flatOk,
                "SSB به‌مدت ${faDigits(flatBars)} کندل بسته تخت؛ حد لازم ${faDigits(MIN_FLAT_BARS)} کندل.",
                isCore = true,
            ),
            Rule(
                2,
                "۲. چرخش Senkou Span A برای ابر آینده",
                spanATurnOk,
                String.format(
                    Locale.US,
                    "شیب قبلی=%+.2f ATR · اکنون=%+.2f ATR (حد چرخش %.3f)",
                    spanAPrevSlopeAtr,
                    spanASlopeAtr,
                    MIN_SPAN_A_TURN_ATR,
                ),
                isCore = true,
            ),
            Rule(
                3,
                "۳. کراس هم‌جهت Tenkan/Kijun در ۸ کندل",
                crossOk,
                "سن کراس: ${if (crossAgeBars == Int.MAX_VALUE) "یافت نشد" else "${faDigits(crossAgeBars)} کندل"}؛ " +
                    "مهلت مجاز ${faDigits(MAX_CROSS_AGE_BARS)} کندل.",
                isCore = true,
            ),
            Rule(
                4,
                "۴. شیب همسو و نبود مخالفت شدید Kijun",
                slopeOk,
                slopeDetailFa.ifBlank { "شیب Tenkan/Kijun/Senkou A سنجیده شد." },
                isCore = false,
            ),
            Rule(
                5,
                "۵. فاصله و گسترش سنجیدهٔ Tenkan/Kijun",
                lineSpacingOk,
                spacingDetailFa.ifBlank { "فاصله و گسترش تنکان/کیجون سنجیده شد." },
                isCore = false,
            ),
            Rule(
                6,
                "۶. آزادی Chikou",
                chikouOpen,
                if (chikouOpen) "چیکو از کندل، TK و ابر در مسیر معامله آزاد است."
                else "چیکو هنوز درگیر ساختار متناظر است.",
                isCore = false,
            ),
            Rule(
                7,
                "۷. کیفیت حرکت شارپ و تأیید حجم/ATR",
                sharpMoveOk,
                sharpDetailFa.ifBlank { "بدنه، حجم و گسترش ATR سنجیده شد." },
                isCore = false,
            ),
            Rule(
                8,
                "۸. سمت قیمت و نبود تضاد تایم‌فریم بالاتر",
                cloudSideOk,
                "قیمت نسبت به Kijun و تایم‌فریم بالاتر با جهت معامله سنجیده شد.",
                isCore = false,
            ),
        )
        // توجه: شرط «۹. پلن نهایی و R:R حداقل ۱:۱٫۵» عمداً این‌جا نیست — به پلن معامله
        // وابسته است که پیش از این تابع ساخته نمی‌شود، و در `IchimokuStrategyEngine`
        // به همین فهرست اضافه می‌شود. اگر این‌جا `true` گزارش می‌شد، شرط نهم به یک
        // تیک بی‌معنا تبدیل می‌شد.

        val corePassed = flatOk && spanATurnOk && crossOk
        val qualityScore = listOf(4 to slopeOk, 5 to lineSpacingOk, 6 to chikouOpen, 7 to sharpMoveOk, 8 to cloudSideOk)
            .count { it.second }
        return Spec(
            rules = rules,
            corePassed = corePassed,
            qualityScore = qualityScore,
            passed = corePassed && qualityScore >= MIN_QUALITY_SCORE,
        )
    }
}
```

</details>


## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| شیب Tenkan=%+.2f · Kijun=%+.2f · Senkou A=%+.2f ATR/کندل |
| فاصله=%.2f ATR · تغییر فاصله=%+.2f ATR · قیمت تا Kijun=%.2f ATR |
| بدنه=%.0f%% · حجم=%.2fx · گسترش ATR=%.2fx |
| امتداد دامنهٔ ساختار ۵۲ کندلی |
| تاج کراس برابری (نصف اندازهٔ کراس) |
| هدف بهینه پس از کراس برابری |
| هدف حرکتی پس از چرخش Senkou A |
| موج بهینه هوسودا (%.2fR) |
|  + هندسهٔ الیوت–نئوویو (نسخهٔ پیمان، هم‌تراز جامع بیس) |
|  + سپر ضد شدو (%.2f ATR) در دالان [0.80..2.10 ATR] |
|  + قفل سقف درصدی نوسان  |
|  · قبل از دیوار فلت تایم بالاتر ($nearestObstacle) |
| فرمول: پاداش (|TP−Entry| = %s) ÷ ریسک ۱R (|Entry−SL| = %s) = 1:%.2f (در بازه مجاز ۱.۵ تا ۳.۰) |
| عدد مناسب R:R = 1:%.2f |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PARSA_52_LINE_TURN

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "PARSA_52_LINE_TURN" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/core/ParsaLineTurnRulesTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/CustomIchimokuStrategiesTest.kt`

> این استراتژی کد اختصاصی دارد؛ برای مرور کامل، فایل‌های `code/` را جدا بردار.
