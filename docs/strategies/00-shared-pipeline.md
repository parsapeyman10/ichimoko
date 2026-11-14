# ۰۰ · موتور مشترک — چیزی که هر ۵ مدل ایچیموکو با آن سنجیده می‌شود
پنج مدل ایچیموکو (بیس + چهار تک‌مکتبی) یک موتور دارند: `evaluateBitcoinStrategy` در `core/IchimokuStrategyEngine.kt`. تفاوتشان فقط «کدام شرط فعال است» و «R:R پایه» است. برای همین، شرط‌های مشترک این‌جا یک‌بار توضیح داده می‌شوند و در سند هر روش تکرار نمی‌شوند.

## ۱) مسیر یک تصمیم

```text
کندل بسته (m15 + m1h + m4h)
   ↓ عکس لحظه‌ای: ۵ خط ایچیموکو، ابر آیندهٔ ۲۶ کندل، چیکو، ATR، حجم، ER
   ↓ ۱۷ شرط (بعضی مشترک، بعضی مخصوص یک مکتب)
   ↓ محاسبهٔ عدد R:R در بازهٔ قفل‌شدهٔ ۱٫۵۰ تا ۳٫۰۰
   ↓ Trade Plan Guard: جهت، حد ضرر ساختاری، اندازهٔ پوزیشن، فاصله از قیمت
   ↓ دروازه‌های ایمنی و یادگیری عصبی
   ↓ پلن نهایی (یا رد شدن با دلیل مشخص)
```

## ۲) شرط‌های موتور

| # | شرط | مالک |
|---|---|---|
| ۱ | رژیم کوموی ۳ تایم‌فریم (تایم بالاتر/استراتژی/ماشه) | مشترک |
| ۲ | چیدمان ۵ خط سانیاکو (Sanyaku Order) | مشترک |
| ۳ | کراس و شیب پویا Tenkan / Kijun | مکتب پاتل |
| ۴ | جهت و شیب ابر آینده (۲۶ کندل جلوتر) | مشترک |
| ۵ | ضخامت ابر آینده و ایمنی از پیچش (Twist) | هوسودا + پلویل |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۷ | شکست سقف/کف ساختاری چیکو (فیلتر تاتل) | مکتب تاتل |
| ۸ | قانون کش لاستیکی کیجون و نبود C-Clamp | مکتب پاتل |
| ۹ | فیلتر جاذبه فلت SSB، دیوار تاج هیدن کراس و تعادل کاتانا | تاتل + پلویل |
| ۱۰ | هندسه موج N هوسودا، نقطه S و چرخه زمانی | هوسودا |
| ۱۱ | قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol) | مشترک |
| ۱۲ | فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت | مشترک |
| ۱۳ | کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14) | مشترک |
| ۱۴ | فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس | مشترک |
| ۱۵ | شکار اصل روند (فاز Genesis/Core و عدم اشباع) | مشترک |
| ۱۶ | نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket) | مشترک |
| ۱۷ | اقتصاد اجرا — هزینهٔ رفت‌وبرگشت در برابر ۱R | مشترک |

شرط‌های ۱ تا ۱۰ پایهٔ تحلیلی‌اند و از مکتب صاحبشان می‌آیند؛ شرط‌های ۱۱ تا ۱۷ لایهٔ کیفیت اجرا و ایمنی‌اند و روی همهٔ روش‌ها اعمال می‌شوند.

## ۳) عدد R:R از کجا می‌آید
کد، عدد پایهٔ هر روش را داخل بازهٔ قفل‌شده می‌برد و بعد پاداش/جریمهٔ شرایط بازار را اضافه می‌کند (`IchimokuStrategyEngine.kt`، بخش «محاسبهٔ عدد مناسب R:R»):

| مرحله | قاعده در کد |
|---|---|
| پایه | `activeModel.baseOptimalRr.coerceIn(1.50, 3.00)` |
| پاداش فاز روند | Genesis **+۰٫۲۵** · Core-Expansion **+۰٫۱۲** · بقیه **۰** |
| پاداش کارایی جهت‌دار | `((erStrat + erTrig - 0.50) * 0.45)` محدود به **−۰٫۱۵ تا +۰٫۳۵** |
| پاداش حجم | نسبت حجم ≥ ۱٫۲۰ → **+۰٫۱۲** |
| نهایی | جمع، محدود به بازهٔ `[requiredMinRr .. maxAllowedRr]` (سقف ۳٫۰۰) |

## ۴) کد مشترک

**Check 1: 3-TF Kumo Regime Alignment (4H / 1H / 15m)** — خطوط ۲۸۶۵ تا ۲۸۸۲، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 1: 3-TF Kumo Regime Alignment (4H / 1H / 15m) ───────────────
        val stratKumoOk = if (isLong) stratSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo
        val trigKumoOk = if (isLong) trigSnap.isPriceAboveKumo else trigSnap.isPriceBelowKumo
        val macroKumoOk = if (!effectiveConfig.requireHigherTfAlignment || macroSnap == null) {
            true
        } else {
            if (isLong) !macroSnap.isPriceBelowKumo else !macroSnap.isPriceAboveKumo
        }
        val cond1Passed = stratKumoOk && trigKumoOk && macroKumoOk
        val cond1Detail = buildString {
            append("${config.triggerInterval.label}/${config.strategyInterval.label}: ")
            append(if (stratSnap.isPriceAboveKumo) "بالای کومو (${stratSnap.kumoTop})" else if (stratSnap.isPriceBelowKumo) "زیر کومو (${stratSnap.kumoBottom})" else "داخل کومو")
            macroSnap?.let { m ->
                append(" · ${config.macroInterval.label}: ")
                append(if (m.isPriceAboveKumo) "بالای کومو" else if (m.isPriceBelowKumo) "زیر کومو" else "داخل کومو")
            }
        }
```

**Check 2: Sanyaku 5-Line Order (Price > Tenkan >= Kijun > Kumo)** — خطوط ۲۸۸۳ تا ۲۸۹۴، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 2: Sanyaku 5-Line Order (Price > Tenkan >= Kijun > Kumo) ─────
        val sanyakuOk = if (isLong) {
            stratSnap.sanyakuBullishOrder && trigSnap.sanyakuBullishOrder
        } else {
            stratSnap.sanyakuBearishOrder && trigSnap.sanyakuBearishOrder
        }
        val cond2Detail = if (isLong) {
            "قیمت > تنکان (${trigSnap.tenkan}) ≥ کیجون (${trigSnap.kijun}) > کومو (${trigSnap.kumoTop})"
        } else {
            "قیمت < تنکان (${trigSnap.tenkan}) ≤ کیجون (${trigSnap.kijun}) < کومو (${trigSnap.kumoBottom})"
        }
```

**Check 4: Future Kumo Direction & Non-diverging Span B (26 bars ahead)** — خطوط ۲۹۱۴ تا ۲۹۲۵، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 4: Future Kumo Direction & Non-diverging Span B (26 bars ahead) ──
        val futureKumoOk = if (!effectiveConfig.requireFutureKumoSlope) {
            true
        } else if (isLong) {
            stratSnap.isFutureKumoBullish && stratSnap.isFutureSpanARising && stratSnap.isFutureSpanBNonFalling &&
                trigSnap.isFutureKumoBullish
        } else {
            stratSnap.isFutureKumoBearish && stratSnap.isFutureSpanAFalling && stratSnap.isFutureSpanBNonRising &&
                trigSnap.isFutureKumoBearish
        }
        val cond4Detail = "Future SSA=${stratSnap.futureSpanA} vs SSB=${stratSnap.futureSpanB} (${if (stratSnap.isFutureKumoBullish) "صعودی" else "نزولی"})"
```

**Check 6: Chikou Span 3D Open Space (26 bars back + 6-bar corridor)** — خطوط ۲۹۳۷ تا ۲۹۵۰، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 6: Chikou Span 3D Open Space (26 bars back + 6-bar corridor) ──
        val chikouOk = if (!effectiveConfig.requireChikouOpenSpace) {
            true
        } else if (isLong) {
            stratSnap.chikouBullishOpenSpace && trigSnap.chikouBullishOpenSpace
        } else {
            stratSnap.chikouBearishOpenSpace && trigSnap.chikouBearishOpenSpace
        }
        val cond6Detail = if (chikouOk) {
            "چیکو در ${config.strategyInterval.label} و ${config.triggerInterval.label} آزاد از کندل، TK و ابر کومو"
        } else {
            "چیکو درگیر کندل‌ها یا ابر ۲۶ دوره قبل است"
        }
```

**Compute Sniper Entry Zone & Tight Structural SL (متود یکپارچه با جامع بیس)** — خطوط ۳۰۹۵ تا ۳۱۶۳، `core/IchimokuStrategyEngine.kt`

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
        val specialStopAnchor: Double? = null
        val rawTechnicalSl = specialStopAnchor?.let { structural ->
            if (isLong) min(baseTechnicalSl, structural) else max(baseTechnicalSl, structural)
        } ?: baseTechnicalSl

        val rawStopDist = max(
            abs(sniperOptimal - rawTechnicalSl),
            abs(livePrice - rawTechnicalSl),
        ).coerceAtLeast(minSafeStopDist)

        // حد ضرر ساختاری (Kijun / Senkou B / 5-Bar Fractal + ATR Buffer) کاملاً ثابت و غیرپله‌ای است
        val technicalSl = rawTechnicalSl

        val stopDist = max(
            abs(sniperOptimal - technicalSl),
            abs(livePrice - technicalSl),
        ).coerceAtLeast(minSafeStopDist)
```

**Check 11: Price Action Candle Body, Close Location, Volume Momentum & Drive PDF Book Rules** — خطوط ۳۱۷۴ تا ۳۲۲۱، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 11: Price Action Candle Body, Close Location, Volume Momentum & Drive PDF Book Rules ──
        val directionalCandleOk = if (isLong) {
            trigSnap.close > trigSnap.open && trigSnap.closeLocationValue >= 0.45 && trigSnap.candleBodyRatio >= 0.22
        } else {
            trigSnap.close < trigSnap.open && trigSnap.closeLocationValue <= 0.55 && trigSnap.candleBodyRatio >= 0.22
        }
        val volumeMomentumOk = trigSnap.volumeRatio20 >= effectiveConfig.minVolumeRatio20
        // استراتژی ۱۶ در نسخهٔ الیوت‌محور شرط اجماع چندلایه ندارد؛ پس این پرچم «همیشه درست»
        // است و در شرط ۱۱ حذف شده تا یک شرط مرده در محاسبه و در گزارش باقی نماند.
        val cond11Passed = directionalCandleOk && volumeMomentumOk
        val driveBookExtraDetail = ""
        val cond11Detail = String.format(
            Locale.US,
            "بدنه کندل: %.0f%% · CLV: %.0f%% · حجم به میانگین ۲۰: %.2fx%s",
            trigSnap.candleBodyRatio * 100.0,
            trigSnap.closeLocationValue * 100.0,
            trigSnap.volumeRatio20,
            driveBookExtraDetail,
        )

        val requiredMinRr = effectiveConfig.minRiskReward.coerceIn(1.50, 2.85)
        val maxAllowedRr = 3.00

        // Check 13 & 15 early inputs so Optimal R:R (عدد مناسب R:R) can adapt to Trend Phase & Efficiency
        val erStrat = stratSnap.efficiencyRatio14
        val erTrig = trigSnap.efficiencyRatio14
        val minErReq = effectiveConfig.minEfficiencyRatio
        val dualFlatChop = stratSnap.isKijunFlat && stratSnap.isCurrentSpanBFlat &&
            trigSnap.isKijunFlat && trigSnap.isCurrentSpanBFlat && erStrat < minErReq
        val htfKijunCounterTrap = if (isLong) {
            trigSnap.close > trigSnap.kijun && stratSnap.close < stratSnap.kijun - stratSnap.atr14 * 0.25
        } else {
            trigSnap.close < trigSnap.kijun && stratSnap.close > stratSnap.kijun + stratSnap.atr14 * 0.25
        }
        val cond13Passed = (erStrat >= minErReq || erTrig >= (minErReq + 0.02)) && !dualFlatChop && !htfKijunCounterTrap

        val waveBars = wave?.barsSinceWaveStart ?: 12
        // توجه (قرارداد ۳.۹.۵۶): `!cond13Passed` عمداً باقی می‌ماند. شرط ۱۵ از شرط ۱۳ مشتق می‌شود و
        // این «وزن دوگانه» طراحی‌شده است، نه باگ: شکست فیلتر رِنج باید هم به‌عنوان فیلتر و هم
        // به‌عنوان فاز، ورود را ببندد. حذف این کوپل، محافظ رِنج را ضعیف می‌کند و آزمون
        // `BaseStrategyDeepDiveTest.flat range is still rejected` دقیقاً همین را می‌پاید.
        val trendPhase = when {
            !cond13Passed || dualFlatChop -> TrendPhase.CHOP_RANGE
            !notOverextended || (waveBars > 55 && trigSnap.isTenkanFlat && stratSnap.isTenkanFlat) -> TrendPhase.LATE_EXHAUSTION
            waveBars <= 18 || trigSnap.recentKatanaRelease || triggerType == StrategyTriggerType.EARLY_KATANA_GENESIS -> TrendPhase.GENESIS
            else -> TrendPhase.CORE_EXPANSION
        }
```

**محاسبه «عدد مناسب R:R» (Optimal R:R) در بازه قفل‌شده [1:1.50 .. 1:3.00] برای هر مدل** — خطوط ۳۲۲۲ تا ۳۳۰۱، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── محاسبه «عدد مناسب R:R» (Optimal R:R) در بازه قفل‌شده [1:1.50 .. 1:3.00] برای هر مدل ──
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
            chosenWavePair != null -> chosenWavePair.second
            isLong -> entryAnchorForTp + optTpDist
            else -> (entryAnchorForTp - optTpDist).coerceAtLeast(0.00001)
        }
        val selectedWaveLabel = when {
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

**Check 12: Room to HTF Plateau & Optimal R:R strictly in [1:1.50 .. 1:3.00]** — خطوط ۳۳۰۲ تا ۳۳۴۰، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 12: Room to HTF Plateau & Optimal R:R strictly in [1:1.50 .. 1:3.00] ──
        val unclampedTp = if (nearestObstacle != null) {
            val distToObstacle = min(abs(nearestObstacle - livePrice), abs(nearestObstacle - sniperOptimal))
            if (distToObstacle / stopDist >= requiredMinRr) {
                val bufferedObstacle = if (isLong) {
                    nearestObstacle - trigSnap.atr14 * 0.08
                } else {
                    nearestObstacle + trigSnap.atr14 * 0.08
                }
                val candidateCapped = if (isLong) min(rawPrimaryTp, bufferedObstacle) else max(rawPrimaryTp, bufferedObstacle)
                if (min(abs(candidateCapped - livePrice), abs(candidateCapped - sniperOptimal)) / stopDist >= requiredMinRr) {
                    candidateCapped
                } else {
                    rawPrimaryTp
                }
            } else {
                rawPrimaryTp
            }
        } else {
            rawPrimaryTp
        }

        // قفل قطعی تارگت سود در بازه [1.50R .. 3.00R] نسبت به نقطه ورود.
        // هر دو کران با **یک** لنگر (`sniperOptimal` = همان قیمت ورودی پلن) و **یک** ۱R
        // (`stopDist` = همان ۱R شرط ۱۲ و انتخاب موج) سنجیده می‌شوند. پیش‌تر کران پایین روی
        // «ورود دورتر از SL» و کران بالا روی «ورود نزدیک‌تر به SL» بسته می‌شد؛ نتیجه این بود که
        // روی شکاف کوچک بین تیک زنده و ناحیهٔ اسنایپر، دالان به سمت سقف ۳R جمع می‌شد و هدف از
        // R:R بهینهٔ همان معامله فاصله می‌گرفت. این فرمول حالا دقیقاً همان حسابی است که
        // `TradePlanGuard.sanitizePlan` هم با آن کار می‌کند، پس دو لایه یکدیگر را نقض نمی‌کنند.
        val effectiveTp = if (isLong) {
            val minAllowedTp = sniperOptimal + stopDist * requiredMinRr
            val maxAllowedTp = sniperOptimal + stopDist * maxAllowedRr
            round2(unclampedTp.coerceIn(minAllowedTp, max(minAllowedTp, maxAllowedTp)))
        } else {
            val minAllowedTp = (sniperOptimal - stopDist * requiredMinRr).coerceAtLeast(0.00001)
            val maxAllowedTp = (sniperOptimal - stopDist * maxAllowedRr).coerceAtLeast(0.00001)
            round2(unclampedTp.coerceIn(min(minAllowedTp, maxAllowedTp), minAllowedTp))
        }
```

**موتور واحد ایمن‌سازی پلن (Trade Plan Guard)** — خطوط ۳۳۴۱ تا ۳۴۳۱، `core/IchimokuStrategyEngine.kt`
<details><summary>کد کامل (۹۰ خط)</summary>

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


**Check 13: Directional Efficiency Ratio (Kaufman ER) & Anti-Chop Filter** — خطوط ۳۴۳۲ تا ۳۴۴۰، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 13: Directional Efficiency Ratio (Kaufman ER) & Anti-Chop Filter ──
        val cond13Detail = String.format(
            Locale.US,
            "کارایی روند (ER14): %.0f%% (${config.strategyInterval.label}) · %.0f%% (${config.triggerInterval.label})%s",
            erStrat * 100.0,
            erTrig * 100.0,
            if (htfKijunCounterTrap) " · تله خلاف کیجون 1H" else if (dualFlatChop) " · رِنج افقی" else " · روند خالص",
        )
```

**Check 14: Anti-Fakeout Wick Guard & Liquidity Sweep Filter** — خطوط ۳۴۴۱ تا ۳۴۵۳، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 14: Anti-Fakeout Wick Guard & Liquidity Sweep Filter ────────
        val adverseWick = if (isLong) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
        val favorableTail = if (isLong) trigSnap.lowerWickRatio else trigSnap.upperWickRatio
        val maxWickAllowed = effectiveConfig.maxAdverseWickRatio
        val cond14Passed = adverseWick <= maxWickAllowed && (trigSnap.candleBodyRatio >= 0.22 || favorableTail >= 0.25)
        val cond14Detail = String.format(
            Locale.US,
            "شدوی مخالف: %.0f%% (سقف مجاز %.0f%%) · شدوی تایید: %.0f%%",
            adverseWick * 100.0,
            maxWickAllowed * 100.0,
            favorableTail * 100.0,
        )
```

**Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion)** — خطوط ۳۴۵۴ تا ۳۴۵۷، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion) ──
        val cond15Passed = trendPhase == TrendPhase.GENESIS || trendPhase == TrendPhase.CORE_EXPANSION
        val cond15Detail = "${trendPhase.badgeFa} · کندل $waveBars موج · فاصله کیجون: ${String.format(Locale.US, "%.2f", extAtr)} ATR"
```

**Check 16: Sniper Entry Precision & Anti-Chase Pocket** — خطوط ۳۴۵۸ تا ۳۴۶۲، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 16: Sniper Entry Precision & Anti-Chase Pocket ──────────────
        val distFromTenkanAtr = abs(trigSnap.close - activeTenkanRef) / trigSnap.atr14.coerceAtLeast(1e-8)
        val maxSniperDist = effectiveConfig.maxSniperDistanceAtr.coerceIn(0.20, 0.55)
        val cond16Passed = distToSniperAtr <= maxSniperDist && distFromTenkanAtr <= (maxSniperDist + 0.50)
```

**Check 17 — اقتصاد معامله: نسبت هزینهٔ اجرا به ۱R** — خطوط ۳۴۶۳ تا ۳۴۸۷، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── Check 17 — اقتصاد معامله: نسبت هزینهٔ اجرا به ۱R ─────────────────
        // ایچیموکو هندسهٔ معامله را از ATR می‌سازد، ولی هزینه (کمیسیون+اسپرد) درصدی از قیمت
        // است. وقتی ۱R کوچک باشد، هزینه بخش بزرگی از ریسک را می‌خورد و انتظار ریاضی منفی
        // می‌شود — مستقل از اینکه تحلیل چقدر درست بوده. این تنها شرطی است که «کیفیت ستاپ»
        // را به «اقتصاد اجرا» وصل می‌کند و روی نمودار هم قابل دفاع است.
        val oneRDist = abs(sniperOptimal - finalStopLoss).coerceAtLeast(1e-9)
        val costToRiskRatio = (roundTripCostPct(normalizedSymbol, sniperOptimal) * sniperOptimal / oneRDist)
        val cond17Passed = costToRiskRatio <= MAX_COST_TO_RISK_RATIO
        val cond17Detail = String.format(
            Locale.US,
            "هزینهٔ رفت‌وبرگشت %.3f%% قیمت = %.2fR از ۱R (%.0f%%) · سقف مجاز %.0f%%",
            roundTripCostPct(normalizedSymbol, sniperOptimal) * 100.0,
            costToRiskRatio,
            costToRiskRatio * 100.0,
            MAX_COST_TO_RISK_RATIO * 100.0,
        )
        val cond16Detail = String.format(
            Locale.US,
            "ورود بهینه: %s · بازه نقطه‌زنی: [%s .. %s] · دقت نقطه‌زنی: %.0f%%",
            round2(sniperOptimal).toString(),
            round2(sniperLow).toString(),
            round2(sniperHigh).toString(),
            sniperEntryScore,
        )
```

**ماشهٔ «فشردگی → انبساط» (فقط بیس و فقط با Feature Flag)** — خطوط ۳۴۸۸ تا ۳۵۷۳، `core/IchimokuStrategyEngine.kt`

```kotlin
        // ── ماشهٔ «فشردگی → انبساط» (فقط بیس و فقط با Feature Flag) ─────────────
        // چرا این‌جا: تحلیل برای تصمیم ورود به `planUsable` و `R:R` نیاز دارد و آن‌ها
        // فقط بعد از `TradePlanGuard` معلوم می‌شوند. پیش از این نقطه هیچ محاسبه‌ای
        // انجام نمی‌شود، پس با `compressionExpansion.enabled = false` هزینهٔ صفر دارد.
        val compressionAnalysis: CompressionExpansionEngine.Analysis? = if (
            isBaseStrategyModel && effectiveConfig.compressionExpansion.enabled
        ) {
            val spaceToObstacleR = nearestObstacle?.let {
                min(abs(it - livePrice), abs(it - sniperOptimal)) /
                    finalStopDist.coerceAtLeast(1e-8)
            }
            CompressionExpansionEngine.analyze(
                closedTriggerBars = closedTriggerBars,
                snapshot = trigSnap,
                config = effectiveConfig.compressionExpansion,
                isLong = isDirectionalLong,
                gates = CompressionExpansionEngine.Gates(
                    higherTfOpposes = macroSnap != null && if (isDirectionalLong) {
                        macroSnap.isPriceBelowKumo
                    } else {
                        macroSnap.isPriceAboveKumo
                    },
                    chikouBlocked = if (isDirectionalLong) {
                        !trigSnap.chikouBullishOpenSpace
                    } else {
                        !trigSnap.chikouBearishOpenSpace
                    },
                    futureKumoOpposes = if (isDirectionalLong) {
                        trigSnap.isFutureKumoBearish
                    } else {
                        trigSnap.isFutureKumoBullish
                    },
                    lateExhaustion = trendPhase == TrendPhase.LATE_EXHAUSTION,
                    spaceToObstacleR = spaceToObstacleR,
                    // R:R پلن ایمن‌شده — هزینه‌ها در همان لایه لحاظ شده‌اند.
                    riskRewardAfterCosts = rr,
                    planUsable = guardedPlan.isUsable,
                ),
                noiseFloorPrice = 0.0,
                symbol = normalizedSymbol,
                strategyId = effectiveId,
                triggerIntervalLabel = config.triggerInterval.label,
            )
        } else {
            null
        }
        // اقتصاد اجرا (شرط ۱۷) هرگز بایپس نمی‌شود؛ اگر هزینه ۱R را بخورد، ورود ممنوع است.
        val compressionEntry = compressionAnalysis?.isEntryAllowed == true && cond17Passed
        val compressionTrigger = when {
            !compressionEntry -> null
            compressionAnalysis?.entryMode == CompressionExpansionEngine.EntryMode.RETEST_CONFIRMED ->
                StrategyTriggerType.COMPRESSION_EXPANSION_RETEST
            else -> StrategyTriggerType.COMPRESSION_EXPANSION_BREAKOUT
        }
        // اولویت: اگر ماشهٔ فشردگی معتبر است همان انتخاب می‌شود، وگرنه ماشهٔ قبلی دست‌نخورده.
        val effectiveTriggerType = compressionTrigger ?: triggerType
        val triggerFired = effectiveTriggerType != StrategyTriggerType.NONE

        val baseSixteenConditions = listOf(
            StrategyConditionCheck(1, "۱. رژیم کوموی ۳ تایم‌فریم (${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label})", cond1Passed, cond1Detail),
            StrategyConditionCheck(2, "۲. چیدمان ۵ خط سانیاکو (Sanyaku Order)", sanyakuOk, cond2Detail),
            StrategyConditionCheck(3, "۳. کراس و شیب پویا Tenkan / Kijun", tkAligned, cond3Detail),
            StrategyConditionCheck(4, "۴. جهت و شیب ابر آینده (۲۶ کندل جلوتر)", futureKumoOk, cond4Detail),
            StrategyConditionCheck(5, "۵. ضخامت ابر آینده و ایمنی از پیچش (Twist)", futureThicknessOk, cond5Detail),
            StrategyConditionCheck(6, "۶. رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل", chikouOk, cond6Detail),
            StrategyConditionCheck(7, "۷. شکست سقف/کف ساختاری چیکو (فیلتر تاتل)", chikouPeakClear, cond7Detail),
            StrategyConditionCheck(8, "۸. قانون کش لاستیکی کیجون و نبود C-Clamp", notOverextended, cond8Detail),
            StrategyConditionCheck(
                9,
                "۹. فیلتر جاذبه فلت SSB، دیوار تاج هیدن کراس و تعادل کاتانا",
                flatSsbGravityCleared && hiddenCrossWallClearance,
                cond9Detail,
            ),
            StrategyConditionCheck(10, "۱۰. هندسه موج N هوسودا، نقطه S و چرخه زمانی", cond10Passed, cond10Detail),
            StrategyConditionCheck(11, "۱۱. قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol)", cond11Passed, cond11Detail),
            StrategyConditionCheck(12, "۱۲. فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت", cond12Passed, cond12Detail),
            StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14)", cond13Passed, cond13Detail),
            StrategyConditionCheck(14, "۱۴. فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس", cond14Passed, cond14Detail),
            StrategyConditionCheck(15, "۱۵. شکار اصل روند (فاز Genesis/Core و عدم اشباع)", cond15Passed, cond15Detail),
            StrategyConditionCheck(16, "۱۶. نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket)", cond16Passed, cond16Detail),
            StrategyConditionCheck(17, "۱۷. اقتصاد اجرا — هزینهٔ رفت‌وبرگشت در برابر ۱R", cond17Passed, cond17Detail),
        )

        val conditions: List<StrategyConditionCheck> = baseSixteenConditions
        val requiredIndicesForModel: Set<Int> = (1..17).toSet()
```

## ۵) دروازه‌های ایمنی و یادگیری عصبی
پیش از ثبت هر سفارش، `EntrySafetyGate` و `NeuralGatePolicy` یک لایهٔ جدا هستند: پیش‌فرض‌ها سقفِ پوزیشن هم‌زمان ۰ (یعنی بی‌نهایت)، سقف هر نماد ۰، حداکثر سن سیگنال ۳ کندل، و سقف نسبت هزینه/ریسک ۰٫۲۵. هفت ترمز عصبی هم در `core/NeuralGatePolicy.kt` تعریف شده‌اند. توضیح کامل: `docs/NEURAL_GATE_AND_SAFETY_FA.md`.

## ۶) کد لایهٔ عصبی (کامل)
<details><summary>کد کامل (۱۹۶ خط)</summary>

```kotlin
package com.aurum.edge.core

/**
 * # سیاست گیت عصبی «پیش‌رونده» (Progressive Neural Gate)
 *
 * ## چه چیزی خراب بود
 * گیت پیشین فقط وقتی وارد مدار می‌شد که **۱۰٬۰۰۰** معاملهٔ یادگیری‌شده ثبت شده باشد
 * (`WARMUP_TARGET_TRADES`). در عمل یعنی گیت هرگز جلوی هیچ معامله‌ای را نگرفت: شبکه ۱۵ تلهٔ
 * ممنوعه را کشف می‌کرد، روی هر ۳۰ ردیف رادار «⛔ شرایط پرخطر» می‌نوشت و همان معاملات اجرا می‌شدند.
 *
 * ## سیاست جدید
 * گیت از **همان اولین نمونه** (و با فعال‌سازی صریح کاربر) وارد مدار می‌شود و با رشد داده
 * سخت‌گیرتر می‌شود — نه سهل‌گیرتر. سه پله:
 *
 * | پله | نمونه | فلسفه |
 * |---|---|---|
 * | `PROTECTIVE` | ۱ تا ۲۹ | فقط تله‌های واضح را می‌گیرد (احتمال برد خیلی پایین یا زیان انتظاری منفی) تا دادهٔ کافی جمع شود |
 * | `BALANCED` | ۳۰ تا ۱۴۹ | آستانهٔ برد مثبت و امید ریاضی نامنفی |
 * | `STRICT` | ۱۵۰+ | همان آستانهٔ تیون‌شدهٔ شبکه (مثلاً ۶۲٪) با حاشیهٔ محافظه‌کارانه |
 *
 * قاعدهٔ آهنین: هر خوشهٔ شرطی که شبکه صریحاً «ممنوعه» علامت زده و دست‌کم [MIN_CLUSTER_SAMPLES]
 * نمونه دارد، در **همهٔ** پله‌ها مسدود می‌شود (شواهد مستقیم بر آستانهٔ مدل مقدم است).
 */
object NeuralGatePolicy {

    /** کمینهٔ نمونه برای فعال‌شدن گیت؛ «از همان ابتدا با هر عددی» = ۱. */
    const val MIN_SAMPLES_FOR_GATE: Int = 1

    /** کمینهٔ نمونهٔ یک خوشهٔ شرطی برای اینکه «ممنوعه» بودنش به‌تنهایی کافی باشد. */
    const val MIN_CLUSTER_SAMPLES: Int = 3

    /** پلهٔ سخت‌گیری گیت بر اساس تعداد معاملات یادگیری‌شده. */
    enum class Stage(val labelFa: String, val minSamples: Int, val winProbFloorPct: Double, val expectedRFloor: Double) {
        PROTECTIVE("پلهٔ ۱ · محافظ (داده کم)", 1, 28.0, -0.10),
        BALANCED("پلهٔ ۲ · متعادل", 30, 40.0, 0.00),
        STRICT("پلهٔ ۳ · سخت‌گیر", 150, 46.0, 0.05);

        companion object {
            fun forSamples(learnedTotal: Int): Stage = when {
                learnedTotal >= STRICT.minSamples -> STRICT
                learnedTotal >= BALANCED.minSamples -> BALANCED
                else -> PROTECTIVE
            }
        }
    }

    /**
     * نتیجهٔ داوری گیت برای یک سیگنال.
     *
     * @property isActive گیت در مدار است (کاربر فعال کرده و دست‌کم یک نمونه داریم).
     * @property blocksTrade آیا همین سیگنال باید مسدود شود.
     */
    data class Decision(
        val stage: Stage,
        val isActive: Boolean,
        val blocksTrade: Boolean,
        val winProbFloorPct: Double,
        val expectedRFloor: Double,
        val reasonFa: String,
        val summaryFa: String,
    )

    /**
     * داوری ورود.
     *
     * @param learnedTotal تعداد معاملات یادگیری‌شده.
     * @param userActivationEnabled فعال‌سازی صریح گیت توسط کاربر (شرط دوم قبلی؛ حفظ شده).
     * @param winProbabilityPct احتمال برد ترکیبی شبکه/باندیت برای همین شرایط (۰..۱۰۰).
     * @param expectedR امید ریاضی ترکیبی همین شرایط بر حسب R.
     * @param clusterFlaggedNoTrade خوشهٔ شرطی جاری «منطقهٔ ممنوعه» علامت خورده است.
     * @param clusterSamples تعداد نمونه‌های همان خوشه.
     * @param tunedGatePct آستانهٔ آموخته‌شدهٔ گیت (مثلاً ۶۲.۰)؛ ۰ = بی‌اثر.
     */
    fun decide(
        learnedTotal: Int,
        userActivationEnabled: Boolean,
        winProbabilityPct: Double,
        expectedR: Double,
        clusterFlaggedNoTrade: Boolean,
        clusterSamples: Int,
        tunedGatePct: Double,
    ): Decision {
        val stage = Stage.forSamples(learnedTotal)
        val active = userActivationEnabled && learnedTotal >= MIN_SAMPLES_FOR_GATE
        val safeGate = if (tunedGatePct.isFinite() && tunedGatePct > 0.0) tunedGatePct else 0.0

        // آستانهٔ برد مؤثر: کف پله، اما هرگز سخاوتمندانه‌تر از آستانهٔ تیون‌شده (با حاشیهٔ ۰٫۸۴).
        val stageFloor = stage.winProbFloorPct
        val tunedFloor = if (safeGate > 0.0) safeGate * 0.84 else 0.0
        val winFloor = if (tunedFloor > 0.0) maxOf(stageFloor, tunedFloor) else stageFloor
        val rFloor = stage.expectedRFloor

        val directClusterTrap = clusterFlaggedNoTrade && clusterSamples >= MIN_CLUSTER_SAMPLES
        val lowWin = winProbabilityPct.isFinite() && winProbabilityPct < winFloor
        val badR = expectedR.isFinite() && expectedR < rFloor

        if (!active) {
            return Decision(
                stage = stage,
                isActive = false,
                blocksTrade = false,
                winProbFloorPct = winFloor,
                expectedRFloor = rFloor,
                reasonFa = "گیت با فعال‌سازی شما روشن نشده است؛ فقط هشدار داده می‌شود.",
                summaryFa = "⚪ گیت غیرفعال · ${stage.labelFa}",
            )
        }

        val reasons = buildList {
            if (directClusterTrap) {
                add("این خوشهٔ شرطی با ${clusterSamples} نمونه «منطقهٔ ممنوعه» علامت خورده است")
            }
            if (lowWin) {
                add("احتمال برد ${fmtPct(winProbabilityPct)} کمتر از کف ${fmtPct(winFloor)} است")
            }
            if (badR) {
                add("امید ریاضی ${fmtR(expectedR)} کمتر از کف ${fmtR(rFloor)} است")
            }
        }
        val blocks = reasons.isNotEmpty()
        return Decision(
            stage = stage,
            isActive = true,
            blocksTrade = blocks,
            winProbFloorPct = winFloor,
            expectedRFloor = rFloor,
            reasonFa = if (blocks) reasons.joinToString(" · ") else "شرایط از کف ${stage.labelFa} عبور کرد.",
            summaryFa = buildString {
                append(if (blocks) "🛑 گیت ${stage.labelFa}" else "🟢 گیت ${stage.labelFa}")
                append(" · کف برد ${fmtPct(winFloor)} · کف R ${fmtR(rFloor)}")
                append(" · نمونه ${learnedTotal}")
            },
        )
    }

    // ─────────────────────── درگاه بک‌تست ───────────────────────

    /**
     * خواستهٔ گیت برای یک ورودِ نامزد در بک‌تست.
     *
     * این کلاس در فایل «خالص» (بدون وابستگی اندروید) نگه داشته می‌شود تا
     * `BacktestReplayEngine` بتواند در محیط آزمایشی JVM هم کامپایل و آزمون شود.
     * پیاده‌سازی واقعی — که شبکهٔ عصبی را می‌پرسد — در `BacktestNeuralTrainer` است.
     */
    data class GateRequest(
        val strategyId: String,
        val symbol: String,
        /** جهت معامله؛ برای نمایش و کلید خوشه. */
        val isLong: Boolean,
        /** تایم‌فریم ماشه؛ بخشی از بردار ویژگی شبکه است. */
        val triggerInterval: com.aurum.edge.domain.Interval,
        /** اعتماد موتور استراتژی در همین کندل (۰..۱۰۰). */
        val engineConfidencePct: Double,
        val plannedRiskReward: Double,
        val entryTime: Long,
        /**
         * گزارش کامل استراتژی در همین کندل.
         *
         * بدون این، گیت مجبور می‌شد ویژگی‌ها را از هوا بسازد. با آن، همان بردار ویژگی‌ای
         * ساخته می‌شود که هنگام آموزش از معاملهٔ بسته‌شده ساخته می‌شد — پس گیت دقیقاً
         * همان چیزی را می‌بیند که شبکه یاد گرفته است.
         */
        val report: StrategyEvaluationReport? = null,
    )

    /** پاسخ گیت به یک ورودِ نامزد. */
    data class GateVerdict(
        val blocks: Boolean,
        val reasonFa: String,
        val summaryFa: String,
    ) {
        companion object {
            val ALLOW = GateVerdict(false, "گیت مانع نشد.", "🟢 مانع ورود ندارد")
        }
    }

    /**
     * درگاهی که بک‌تست قبل از بازکردن هر معامله از آن می‌پرسد.
     *
     * چرا این لازم بود: کلید «مانع» در بخش هوش عصبی تا امروز **هیچ اثری در بک‌تست
     * نداشت**، چون `BacktestReplayEngine` هیچ‌جا `NeuralGatePolicy.decide` را صدا
     * نمی‌زد. نتیجه همان چیزی بود که کاربر دید: «با مانع و بدون مانع یکی بود».
     */
    fun interface EntryGate {
        fun judge(request: GateRequest): GateVerdict

        companion object {
            /** گیت خاموش: هیچ ورودی مسدود نمی‌شود (رفتار پیش‌فرض و سازگار با قبل). */
            val ALLOW_ALL: EntryGate = EntryGate { GateVerdict.ALLOW }
        }
    }

    private fun fmtPct(v: Double): String = "${(kotlin.math.round(v * 10.0) / 10.0)}%"

    private fun fmtR(v: Double): String = String.format(java.util.Locale.US, "%+.2fR", v)
}
```

</details>

