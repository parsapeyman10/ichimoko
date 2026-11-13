# ۱۹ · هیدن کراس · کراس برابری، تاج و ابر مخفی
`HIDDEN_CROSS` · هیدن کراس · ابر مخفی + تاج

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `HIDDEN_CROSS` |
| نشان | هیدن کراس · ابر مخفی + تاج |
| مکتب‌ها | — (روش مستقل؛ شرط‌های خودش را دارد) |
| تعداد مکتب | — (روش مستقل) |
| R:R پایه | ۲.۰ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | روش مستقل هیدن کراس · ابر مخفی تنکان/کیجون، کراس برابری و بازگشت به تاج |
| انتخاب صریح کاربر | بله — پیش‌فرض خاموش |
| یادگیری عصبی | مستثنا (بدون آموزش/گیت) |

## خلاصه
دو مفهوم کمترشناخته‌شدهٔ ایچیموکو در یک روش: ۱) «ابر مخفی» یعنی نوارِ بین تنکان و کیجون؛ 

پنج شرط، دقیقاً همان چیزی که در اسلایدهای استاد بود: ورود روی بازگشت به خط تاج، نه روی شکست.

## شرط‌ها
موتور اختصاصی: `core/HiddenCrossEngine.kt`

| # | شرط |
|---|---|
| ۱ | ابر مخفی باریک (فاصلهٔ تنکان از کیجون) |
| ۲ | جهت از ابر مخفی (ترتیب تنکان/کیجون + قیمت در سمت روند) |
| ۳ | آزادی چیکو (خط تأخیر جدا از قیمت) |
| ۴ | کراس برابری بالغ (هم‌پوشانی گذشته، بازگشت ممکن) |
| ۵ | بازگشت قیمت به تاج و رد شدن از آن |

| ثابت | مقدار |
|---|---|
| `EQUAL_CROSS_TOLERANCE_ATR` | `0.06` |
| `MIN_EQUAL_CROSS_BARS` | `3` |
| `CROWN_EXTENSION_RATIO` | `0.5` |
| `MIN_RETEST_AGE_BARS` | `5` |
| `MAX_EQUAL_CROSS_AGE_BARS` | `120` |
| `CROWN_TOUCH_ATR` | `0.35` |
| `CROWN_REJECT_ATR` | `0.05` |
| `HIDDEN_CLOUD_MAX_THICKNESS_ATR` | `0.55` |
| `ANALOGUE_WINDOW` | `8` |
| `ANALOGUE_HORIZON` | `10` |
| `ANALOGUE_LOOKBACK` | `240` |
| `ANALOGUE_MIN_SAMPLES` | `6` |
| `ANALOGUE_MIN_AGREEMENT` | `0.55` |
| `HIDDEN_CROSS_MIN_TARGET_R` | `1.50` |
| `MIN_CLOSED_BARS` | `60` |


## کد
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
**روش مستقل «هیدن کراس»: ابر مخفی جهت می‌دهد، کراس برابری هدف می‌سازد** — خطوط ۳۸۴۳ تا ۳۹۴۵
<details><summary>کد کامل (۱۰۲ خط)</summary>

```kotlin
        // ── روش مستقل «هیدن کراس»: ابر مخفی جهت می‌دهد، کراس برابری هدف می‌سازد ──
        // دادهٔ لازم از لایهٔ snapshot می‌آید تا محاسبات یک‌بار و مشترک انجام شوند.
        val hcCloud = trigSnap
        val hcTkBullish = hcCloud.tenkan > hcCloud.kijun
        val hcPriceAboveCloud = hcCloud.close > hcCloud.hiddenCloudTop
        val hcPriceBelowCloud = hcCloud.close < hcCloud.hiddenCloudBottom
        // شرط ۱: جهت از ابر مخفی — قیمت بیرون از نوار و ترتیب TK هم‌جهت
        val hcDirectionOk = if (isDirectionalLong) {
            hcPriceAboveCloud && hcTkBullish && hcCloud.hiddenCloudSlopeAtrPerBar >= -0.01
        } else {
            hcPriceBelowCloud && !hcTkBullish && hcCloud.hiddenCloudSlopeAtrPerBar <= 0.01
        }
        // شرط ۲: نوار باید باریک و «تعادل‌مانند» باشد (ابر مخفی واقعی)
        val hcCloudBalancedOk = hcCloud.isHiddenCloudBalanced &&
            hcCloud.hiddenCloudThicknessAtr >= 0.01
        // شرط ۳: کراس برابری موجود و تازه
        val hcCrossExistsOk = hcCloud.equalCrossLevel > 0.0 &&
            hcCloud.equalCrossBars >= HiddenCrossEngine.MIN_EQUAL_CROSS_BARS
        val hcCrossFreshOk = hcCrossExistsOk &&
            hcCloud.equalCrossBarsSinceEnd <= HiddenCrossEngine.MAX_EQUAL_CROSS_AGE_BARS
        // شرط ۴: سطح برابری در سمت درست قیمت باشد (قیمت از ناحیهٔ برابری بیرون زده)
        val hcCrossSideOk = hcCrossExistsOk && if (isDirectionalLong) {
            hcCloud.close > hcCloud.equalCrossLevel
        } else {
            hcCloud.close < hcCloud.equalCrossLevel
        }
        // شرط ۵: تأیید آماری آنالوگ — اگر نمونهٔ کافی نبود، ادعایی مطرح نمی‌شود
        val hcAnalogueMeasured = hcCloud.hiddenCloudAnalogueSamples >= HiddenCrossEngine.ANALOGUE_MIN_SAMPLES
        val hcAnalogueOk = if (!hcAnalogueMeasured) {
            true
        } else {
            val agrees = if (isDirectionalLong) {
                hcCloud.hiddenCloudAnalogueForwardAtr > 0.0
            } else {
                hcCloud.hiddenCloudAnalogueForwardAtr < 0.0
            }
            agrees && hcCloud.hiddenCloudAnalogueAgreement >= HiddenCrossEngine.ANALOGUE_MIN_AGREEMENT
        }
        // شرط ۶: هدف تاج در مسیر و در دالان R:R
        val hcRrRefStop = max(
            abs(hcCloud.close - if (isDirectionalLong) hcCloud.hiddenCloudBottom else hcCloud.hiddenCloudTop),
            hcCloud.atr14 * 0.80,
        ).coerceAtLeast(1e-8)
        val hcCrownDistanceR = if (hcCloud.crownLevel > 0.0) {
            abs(hcCloud.crownLevel - hcCloud.close) / hcRrRefStop
        } else 0.0
        val hcCrownAheadOk = hcCloud.crownLevel > 0.0 && if (isDirectionalLong) {
            hcCloud.crownLevel > hcCloud.close
        } else {
            hcCloud.crownLevel < hcCloud.close
        }
        val hcTargetRoomOk = hcCrownAheadOk && hcCrownDistanceR >= HiddenCrossEngine.HIDDEN_CROSS_MIN_TARGET_R
        // شرط ۷: کیفیت کندل ماشه و نبود کشیدگی از نوار
        val hcImpulseOk = hcCloud.candleBodyRatio >= 0.32 &&
            (if (isDirectionalLong) hcCloud.closeLocationValue >= 0.52 else hcCloud.closeLocationValue <= 0.48)
        val hcNotOverextended = max(hcCloud.distanceToKijunAtr, hcCloud.hiddenCloudThicknessAtr) <= 1.85
        // شرط ۸: اجازهٔ تایم‌فریم بالاتر (اگر وجود داشته باشد)
        val hcMtfOk = macroSnap == null || if (isDirectionalLong) {
            !macroSnap.isPriceBelowKumo
        } else {
            !macroSnap.isPriceAboveKumo
        }
        // شرط ۹: آزادی چیکو در مسیر معامله
        val hcChikouOk = if (isDirectionalLong) {
            hcCloud.chikouBullishOpenSpace
        } else {
            hcCloud.chikouBearishOpenSpace
        }
        // شرط ۱۰: همسویی خطوط (تنکان/کیجون) با جهت
        val hcSlopeOk = if (isDirectionalLong) {
            hcCloud.tenkanSlopeAtrPerBar > 0.0 && hcCloud.kijunSlopeAtrPerBar >= -0.06
        } else {
            hcCloud.tenkanSlopeAtrPerBar < 0.0 && hcCloud.kijunSlopeAtrPerBar <= 0.06
        }
        // ماشهٔ هیدن کراس = همان پنج قاعدهٔ آموزش اصلی (ابر مخفی باریک، جهت، چیکو، کراس
        // برابری، تاج). پیش از این، شرط وابسته به آنالوگ آماری، تازگی، بدنهٔ کندل،
        // هم‌سویی تایم‌فریم و شیب بود — یعنی شرط‌هایی که در عکس‌ها نیستند و در عمل باعث
        // می‌شدند `HIDDEN_CROSS` هیچ‌وقت ماشه نخورد.
        val hcSpecForTrigger = HiddenCrossEngine.spec(
            hcCloud,
            long = isLong,
            tenkanAboveKijun = hcTkBullish,
            chikouFree = hcChikouOk,
        )
        // فقط و فقط همان پنج قاعده. `hcDirectionOk` هم عمداً کنار گذاشته شد چون یک
        // شرط شیب (`hiddenCloudSlopeAtrPerBar`) داشت که در هیچ‌یک از نُه تصویر نیست.
        val hiddenCrossReady = hcSpecForTrigger.allPassed

        val triggerType = when {
            isBtc52Model && btc52Ready -> StrategyTriggerType.BTC_52_STRUCTURE_BREAK
            isParsaModel && parsaReady -> StrategyTriggerType.PARSA_52_LINE_TURN
            isHiddenCrossModel && hiddenCrossReady -> StrategyTriggerType.HIDDEN_CROSS
            isBtc52Model || isParsaModel || isHiddenCrossModel -> StrategyTriggerType.NONE
            else -> detectEntryTrigger(
                direction = candidateDirection,
                bars = closedTriggerBars,
                trigSnap = trigSnap,
                stratSnap = stratSnap,
                config = effectiveConfig,
                activeModel = activeModel,
            )
        }
```

</details>

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

**هیدن کراس: دقیقاً همان چیزی که در آموزش اصلی هست، بدون افزودن شرط** — خطوط ۴۴۸۰ تا ۴۵۶۴

```kotlin
        // ── هیدن کراس: دقیقاً همان چیزی که در آموزش اصلی هست، بدون افزودن شرط ──────
        // پیش از این ۱۰ شرط روی هم سوار شده بود (ضخامت ابر، تازگی کراس، آنالوگ آماری،
        // تاج در مسیر، بدنهٔ کندل، همسویی تایم‌فریم، شیب، چیکو، پلن) — یعنی چیزی که در
        // عکس‌ها نبود. عکس‌ها فقط سه چیز می‌گویند:
        //
        //  ۱. ابر مخفی = فاصلهٔ تنکان از کیجون؛ اگر «باریک» باشد جهت را می‌دهد. در عکس‌ها
        //     همیشه یک ویژگی دیگر هم دیده می‌شود: آزادی چیکو (چیکوی سبز و جدا از قیمت).
        //  ۲. کراس برابری = تنکان و کیجون که «مدتی روی هم افتاده‌اند». اندازهٔ همین
        //     هم‌پوشانی نصف می‌شود و سقف/کف آینده را می‌سازد (خط‌چین قرمز عکس‌ها).
        //  ۳. تاج = از سطح کراس به‌اندازهٔ نصف هم‌پوشانی، در جهت شکست — سطح هدف.
        //
        // پس پنج شرط: ابر مخفی باریک، جهت از بیرون‌زدن قیمت، چیکوی آزاد، کراس برابری
        // موجود (تازگی‌اش)، و تاج واقعاً در مسیر باشد. بقیه حذف شدند.
        val hcSpec = HiddenCrossEngine.spec(
            hcCloud,
            long = isLong,
            tenkanAboveKijun = hcTkBullish,
            chikouFree = hcChikouOk,
        )
        val hiddenCrossConditions = listOf(
            StrategyConditionCheck(
                hcSpec.conditions[0].index,
                hcSpec.conditions[0].titleFa,
                hcSpec.conditions[0].passed,
                hcSpec.conditions[0].detailFa,
            ),
            StrategyConditionCheck(
                hcSpec.conditions[1].index,
                hcSpec.conditions[1].titleFa,
                hcSpec.conditions[1].passed,
                hcSpec.conditions[1].detailFa,
            ),
            StrategyConditionCheck(
                hcSpec.conditions[2].index,
                hcSpec.conditions[2].titleFa,
                hcSpec.conditions[2].passed,
                hcSpec.conditions[2].detailFa,
            ),
            StrategyConditionCheck(
                hcSpec.conditions[3].index,
                hcSpec.conditions[3].titleFa,
                hcSpec.conditions[3].passed,
                hcSpec.conditions[3].detailFa,
            ),
            StrategyConditionCheck(
                hcSpec.conditions[4].index,
                hcSpec.conditions[4].titleFa,
                hcSpec.conditions[4].passed,
                hcSpec.conditions[4].detailFa,
            ),
        )

        val conditions = when {
            isBtc52Model -> btc52Conditions
            isParsaModel -> parsaConditions
            isHiddenCrossModel -> hiddenCrossConditions
            activeModel.isDrivePdfStrategy -> buildElliottWaveConditions(
                isLong = isLong,
                wave = wave,
                selectedWaveLabel = selectedWaveLabel,
                rewardDistSniper = rewardDistSniper,
                cond12Passed = cond12Passed,
                cond12Detail = cond12Detail,
                cond13Passed = cond13Passed,
                cond13Detail = cond13Detail,
                cond14Passed = cond14Passed,
                cond14Detail = cond14Detail,
                cond16Passed = cond16Passed,
                cond16Detail = cond16Detail,
            )
            else -> baseSixteenConditions
        }

        val requiredIndicesForModel: Set<Int> = when {
            isBtc52Model -> btc52Conditions.indices.map { it + 1 }.toSet()
            isHiddenCrossModel -> hiddenCrossConditions.indices.map { it + 1 }.toSet()
            // پارسا: تنها شرایط «هویت روش» (فلت SSB + چرخش Span A + کراس تازه) و شرط
            // نهم (پلن) برای سیگنال لازم‌اند. پنج تأیید کیفیتی با امتیاز در `parsaReady`
            // سنجیده می‌شوند، نه با AND خالص — وگرنه همان «۰ معامله» برمی‌گردد.
            isParsaModel -> parsaSpec.coreRules.map { it.index }.toSet() + 9
            else -> (1..17).toSet()
        }
        val modelCoreConditions = conditions.filter { it.index in requiredIndicesForModel }
        val allSixteenPassed = modelCoreConditions.all { it.passed }
```
### کد کامل این استراتژی (پوشهٔ جداگانه)

| فایل | خط | داخل این سند |
|---|---|---|
| `code/19-hidden-cross/HiddenCrossEngine.kt` | ۸۲۵ | فقط در فایل کپی |

فایل‌های بلندتر از ۳۰۰ خط داخل سند تکرار نشده‌اند تا سند سبک بماند؛ نسخهٔ کاملشان در همان پوشهٔ `code/` و در فایل زیپ هست.

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
tools/strategy_lab/run.sh 8000 11 hx

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "HIDDEN_CROSS" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/core/HiddenCrossRulesTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/CustomIchimokuStrategiesTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/RiskAndCalendarEngineTest.kt`

> این استراتژی کد اختصاصی دارد؛ برای مرور کامل، فایل‌های `code/` را جدا بردار.
