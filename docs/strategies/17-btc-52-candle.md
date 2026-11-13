# ۱۷ · بیت‌کوین · ساختار ۵۲ کندلی و شکست/بازآزمایی
`BTC_52_CANDLE_REGIME` · BTC · پنجرهٔ ۵۲ کندل

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `BTC_52_CANDLE_REGIME` |
| نشان | BTC · پنجرهٔ ۵۲ کندل |
| مکتب‌ها | — (روش مستقل؛ شرط‌های خودش را دارد) |
| تعداد مکتب | — (روش مستقل) |
| R:R پایه | ۲.۲ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | روش مستقل بیت‌کوین · ساختار روند/فشردگی/شکست ۵۲ کندلی |
| انتخاب صریح کاربر | بله — پیش‌فرض خاموش |
| یادگیری عصبی | مستثنا (بدون آموزش/گیت) |

## خلاصه
فقط برای BTC/USDT: سه کندل آخر الگوی تصمیم نیستند. روش، پنجرهٔ ۵۲ کندل بسته را برای جهت و جایگاه قیمت می‌سنجد؛ پس از فشردگی یا همگرایی، شکست معتبر سقف/کف ۵۲ کندلی یا بازآزمایی همان مرز را با شیب‌های ایچیموکو، ابر، آزادی چیکو، حجم و گسترش نوسان تأیید می‌کند. ورودِ تعقیبیِ بیش‌ازحد دور از کیجون رد می‌شود.

ده شرط مستقل روی ساختار ۵۲ کندلی؛ شرط‌هایش با ۱۵ روش ایچیموکو یکی نیست. برای همین فهرست شرط‌هایش جدا نوشته شده است.

## شرط‌ها
این روش شرط‌هایش را جدا حساب می‌کند، نه با فهرست ۱۷تایی:

| # | شرط |
|---|---|
| ۱ | دادهٔ بستهٔ کامل پنجرهٔ ۵۲ کندلی |
| ۲ | جهت و جایگاه ساختاری ۵۲ کندلی |
| ۳ | شکست بسته‌شده یا بازآزمایی مرز ۵۲ کندلی |
| ۴ | فشردگی/همگرایی پیش از گسترش |
| ۵ | همسویی ابر و تایم‌فریم بالاتر |
| ۶ | شیب Tenkan/Kijun و Senkou A |
| ۷ | آزادی Chikou در مسیر معامله |
| ۸ | کیفیت حرکت شارپ (بدنه، حجم یا گسترش ATR) |
| ۹ | فاصلهٔ قابل‌تعقیب از Kijun |
| ۱۰ | پلن نهایی و R:R حداقل ۱:۱٫۵ |


## کد
### شرط‌ها و منطق خودِ روش (کد اصلی)
**جهت و جایگاه ساختاری ۵۲ کندلی** — خطوط ۳۵۵۶ تا ۳۵۷۸

```kotlin
        val long52Context = trigSnap.regressionSlope52AtrPerBar >= 0.025 &&
            trigSnap.closeChange52Atr >= 1.0 && trigSnap.close >= (trigSnap.prior52High + trigSnap.prior52Low) / 2.0
        val short52Context = trigSnap.regressionSlope52AtrPerBar <= -0.025 &&
            trigSnap.closeChange52Atr <= -1.0 && trigSnap.close <= (trigSnap.prior52High + trigSnap.prior52Low) / 2.0
        val parsaBullishStructure = trigSnap.senkouSpanASlopeAtrPerBar >= 0.025 &&
            trigSnap.tenkan > trigSnap.kijun && trigSnap.bullishTenkanKijunCrossAgeBars <= 8
        val parsaBearishStructure = trigSnap.senkouSpanASlopeAtrPerBar <= -0.025 &&
            trigSnap.tenkan < trigSnap.kijun && trigSnap.bearishTenkanKijunCrossAgeBars <= 8
        val candidateDirection = when {
            isBtc52Model && long52Context -> SignalAction.BUY
            isBtc52Model && short52Context -> SignalAction.SELL
            isParsaModel && parsaBullishStructure -> SignalAction.BUY
            isParsaModel && parsaBearishStructure -> SignalAction.SELL
            // هیدن کراس: جهت فقط از هندسهٔ ابر مخفی می‌آید.
            isHiddenCrossModel && trigSnap.close > trigSnap.hiddenCloudTop &&
                trigSnap.tenkan > trigSnap.kijun -> SignalAction.BUY
            isHiddenCrossModel && trigSnap.close < trigSnap.hiddenCloudBottom &&
                trigSnap.tenkan < trigSnap.kijun -> SignalAction.SELL
            bullishBias && !bearishBias -> SignalAction.BUY
            bearishBias && !bullishBias -> SignalAction.SELL
            stratSnap.tenkan >= stratSnap.kijun -> SignalAction.BUY
            else -> SignalAction.SELL
        }
```
**پنجرهٔ بسته و ماشهٔ شکست/بازآزمایی ۵۲ کندلی** — خطوط ۳۷۳۴ تا ۳۷۷۶

```kotlin
        val closedTriggerBars = alignedTriggerCandles.filter { it.closed }.ifEmpty { alignedTriggerCandles }
        val isDirectionalLong = isLong
        val previousBarIndex = closedTriggerBars.lastIndex - 1
        val priorRangeBeforePrevious = if (closedTriggerBars.size >= 54) {
            closedTriggerBars.subList(closedTriggerBars.size - 54, closedTriggerBars.size - 2)
        } else emptyList()
        val previousBar = closedTriggerBars.getOrNull(previousBarIndex)
        val priorHighBeforePrevious = priorRangeBeforePrevious.maxOfOrNull { it.high }
        val priorLowBeforePrevious = priorRangeBeforePrevious.minOfOrNull { it.low }
        val btc52RetestOk = if (isDirectionalLong) {
            val priorHigh = priorHighBeforePrevious
            priorHigh != null && previousBar != null &&
                previousBar.close > priorHigh + trigSnap.atr14 * 0.05 &&
                trigSnap.low <= priorHigh + trigSnap.atr14 * 0.30 &&
                trigSnap.close >= priorHigh - trigSnap.atr14 * 0.08
        } else {
            val priorLow = priorLowBeforePrevious
            priorLow != null && previousBar != null &&
                previousBar.close < priorLow - trigSnap.atr14 * 0.05 &&
                trigSnap.high >= priorLow - trigSnap.atr14 * 0.30 &&
                trigSnap.close <= priorLow + trigSnap.atr14 * 0.08
        }
        val btc52BreakoutOk = if (isDirectionalLong) {
            trigSnap.close > trigSnap.prior52High + trigSnap.atr14 * 0.05 || btc52RetestOk
        } else {
            trigSnap.close < trigSnap.prior52Low - trigSnap.atr14 * 0.05 || btc52RetestOk
        }
        val btc52TrendOk = if (isDirectionalLong) {
            long52Context && trigSnap.tenkanSlopeAtrPerBar > 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar >= -0.02 &&
                (macroSnap == null || !macroSnap.isPriceBelowKumo)
        } else {
            short52Context && trigSnap.tenkanSlopeAtrPerBar < 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar <= 0.02 &&
                (macroSnap == null || !macroSnap.isPriceAboveKumo)
        }
        val btc52SharpMoveOk = trigSnap.candleBodyRatio >= 0.38 &&
            (trigSnap.volumeRatio20 >= 1.05 || trigSnap.atrExpansionRatio52 >= 1.08) &&
            (if (isDirectionalLong) trigSnap.closeLocationValue >= 0.62 else trigSnap.closeLocationValue <= 0.38)
        val btc52ChikouOk = if (isDirectionalLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace
        val btc52Ready = closedTriggerBars.size >= 53 && btc52TrendOk && btc52BreakoutOk &&
            btc52SharpMoveOk && btc52ChikouOk && trigSnap.distanceToKijunAtr <= 1.65 &&
            trigSnap.range52Atr >= 2.0
```
**شرط‌های شارپ/چیکو برای ۵۲ کندلی** — خطوط ۳۷۵۶ تا ۳۷۷۶

```kotlin
        val btc52BreakoutOk = if (isDirectionalLong) {
            trigSnap.close > trigSnap.prior52High + trigSnap.atr14 * 0.05 || btc52RetestOk
        } else {
            trigSnap.close < trigSnap.prior52Low - trigSnap.atr14 * 0.05 || btc52RetestOk
        }
        val btc52TrendOk = if (isDirectionalLong) {
            long52Context && trigSnap.tenkanSlopeAtrPerBar > 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar >= -0.02 &&
                (macroSnap == null || !macroSnap.isPriceBelowKumo)
        } else {
            short52Context && trigSnap.tenkanSlopeAtrPerBar < 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar <= 0.02 &&
                (macroSnap == null || !macroSnap.isPriceAboveKumo)
        }
        val btc52SharpMoveOk = trigSnap.candleBodyRatio >= 0.38 &&
            (trigSnap.volumeRatio20 >= 1.05 || trigSnap.atrExpansionRatio52 >= 1.08) &&
            (if (isDirectionalLong) trigSnap.closeLocationValue >= 0.62 else trigSnap.closeLocationValue <= 0.38)
        val btc52ChikouOk = if (isDirectionalLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace
        val btc52Ready = closedTriggerBars.size >= 53 && btc52TrendOk && btc52BreakoutOk &&
            btc52SharpMoveOk && btc52ChikouOk && trigSnap.distanceToKijunAtr <= 1.65 &&
            trigSnap.range52Atr >= 2.0
```
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
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


## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
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
|  ← هدف: $selectedWaveLabel · 🔒 حد ضرر ساختاری کاملاً ثابت و غیرپله‌ای) |
| عدد مناسب R:R = 1:%.2f (%s · بازه مجاز 1.5..3.0) |
|  · فرمول: $rewardDistSniper ÷ $riskDistSniper |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 btc
tools/strategy_lab/run.sh 8000 11 diag

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "BTC_52_CANDLE_REGIME" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/engine/CustomIchimokuStrategiesTest.kt`
