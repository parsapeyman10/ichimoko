# ۱۶ · استراتژی ۱۶ · استراتژی پیمان (الیوت–نئوویو)
`DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER` · استراتژی پیمان · الیوت · نئوویو

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER` |
| نشان | استراتژی پیمان · الیوت · نئوویو |
| مکتب‌ها | — (روش مستقل؛ شرط‌های خودش را دارد) |
| تعداد مکتب | ۱ از ۴ |
| R:R پایه | ۲.۲۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | Elliott Wave + NeoWave (Glenn Neely) — هندسهٔ موجی |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
استراتژی پیمان — نسخهٔ الیوت‌محور. 

نُه لایهٔ ساختاری که باید هم‌جهت باشند. کدش از موتور ایچیموکو جدا شده و در `core/peyman/` و `NeoWaveEngine` است.

### چطور اجماع ساخته می‌شود

نُه لایه هر کدام یک «کتاب» جدا هستند؛ هر لایه یا پاس است یا رد، و جمعشان تصمیم می‌سازد
(`evaluateUnified9BookConsensus` در `core/IchimokuStrategyEngine.kt`):

| تعداد لایهٔ پاس | R:R پویا |
|---|---|
| ۸ یا بیشتر | ۲٫۸۵ |
| ۷ | ۲٫۷۰ |
| ۶ | ۲٫۵۵ |
| ۵ | ۲٫۴۰ |
| کمتر از ۵ | ۲٫۲۵ (پایه) |

احتمال برد هم جدا حساب می‌شود: جمع وزن‌های شرطی (پیشین ۰٫۱۵ ≈ ۵۳٫۷٪) و بعد
`100 / (1 + e^(−logOdds))` که در بازهٔ **۱۸٪ تا ۹۷٫۵٪** محدود می‌شود. اگر لایهٔ چیکو رد باشد و
کیجون هم تخت باشد، **۰٫۳۵** از وزن کم می‌شود.

> دروازهٔ `bookConfluenceGate` (اجماع مکتبی بیس) فقط روی مدل بیس اثر دارد؛ برای پیمان،
> ۵۲ کندلی، پارسا و هیدن کراس دخالت نمی‌کند و منطق AND داخلی خودشان دست‌نخورده می‌ماند.


## کد
### شرط‌ها و منطق خودِ روش (کد اصلی)
**نُه لایهٔ ساختاری پیمان (شرط‌های خودِ روش)** — خطوط ۳۱۶۳ تا ۳۲۸۳
<details><summary>کد کامل (۱۲۱ خط)</summary>

```kotlin
        // ۱. لایهٔ ساختاری ۱ (TuAnh): BB(20,2) Squeeze/Band + EMA(89)/SMA(200) + RSI(14) > 50
        val book1 = if (isLong) {
            trigSnap.close >= trigSnap.bbMiddle20 &&
                trigSnap.close >= trigSnap.ema89 - atr * 0.15 &&
                trigSnap.rsi14 >= 50.0 &&
                (trigSnap.isBollingerSqueeze || trigSnap.isMarubozuBreakoutBull || trigSnap.close > trigSnap.kumoTop)
        } else {
            trigSnap.close <= trigSnap.bbMiddle20 &&
                trigSnap.close <= trigSnap.ema89 + atr * 0.15 &&
                trigSnap.rsi14 <= 50.0 &&
                (trigSnap.isBollingerSqueeze || trigSnap.isMarubozuBreakoutBear || trigSnap.close < trigSnap.kumoBottom)
        }

        // ۲. لایهٔ ساختاری ۲ (Foka 15m/Day): بیرون کومو + شیب EMA(21) و کیجون + پرش RSI(7) از خط ۵۰ + فرکتال ۵ کندلی
        val book2 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                trigSnap.close >= trigSnap.ema21 - atr * 0.12 &&
                trigSnap.ema21SlopeAtrPerBar > -0.01 &&
                (trigSnap.rsi7Bullish50Bounce || trigSnap.rsi7 >= 51.5) &&
                trigSnap.lastDownFractalLow < trigSnap.close
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.close <= trigSnap.ema21 + atr * 0.12 &&
                trigSnap.ema21SlopeAtrPerBar < 0.01 &&
                (trigSnap.rsi7Bearish50Bounce || trigSnap.rsi7 <= 48.5) &&
                trigSnap.lastUpFractalHigh > trigSnap.close
        }

        // ۳. لایهٔ ساختاری ۳ (Keenbase 10-30-60): کراس نهادی Tenkan(10)/Kijun(30) + بیرون Kumo(60)
        val book3 = if (isLong) {
            trigSnap.tenkan10 >= trigSnap.kijun30 &&
                trigSnap.close > trigSnap.kumoTop103060 &&
                stratSnap.close >= stratSnap.kumoBottom103060
        } else {
            trigSnap.tenkan10 <= trigSnap.kijun30 &&
                trigSnap.close < trigSnap.kumoBottom103060 &&
                stratSnap.close <= stratSnap.kumoTop103060
        }

        // ۴. لایهٔ ساختاری ۴ (Mukherjee Kelly & RSI): حداقل ۳ سیگنال طوفان کامل (Kelly >= 62%) + الگوی شمعی + سلامت RSI
        val book4 = trigSnap.kellySignalCount >= 3 && if (isLong) {
            trigSnap.tenkan >= trigSnap.kijun &&
                trigSnap.rsi14 >= 50.0 &&
                trigSnap.rsiPeakDropLong < 10.5 &&
                (trigSnap.hasBullishCandlestickPattern || trigSnap.candleBodyRatio >= 0.30)
        } else {
            trigSnap.tenkan <= trigSnap.kijun &&
                trigSnap.rsi14 <= 50.0 &&
                trigSnap.rsiTroughRiseShort < 11.5 &&
                (trigSnap.hasBearishCandlestickPattern || trigSnap.candleBodyRatio >= 0.30)
        }

        // ۵. لایهٔ ساختاری ۵ (Mukherjee Clouds & Chikou Filter): خط ماشه تنکان + کیجون غیرفلت + آزادی چیکو
        val book5 = !trigSnap.isKijunFlat && if (isLong) {
            trigSnap.close >= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar >= -0.01 &&
                trigSnap.chikouBullishOpenSpace
        } else {
            trigSnap.close <= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar <= 0.01 &&
                trigSnap.chikouBearishOpenSpace
        }

        // ۶. لایهٔ ساختاری ۶ (HowToTrade Key Level & Kumo Twist): شکست کامل کانال کومو + Twist هم‌جهت ابر آینده
        val book6 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                stratSnap.close > stratSnap.kumoTop &&
                trigSnap.isFutureKumoBullish &&
                stratSnap.isFutureKumoBullish
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                stratSnap.close < stratSnap.kumoBottom &&
                trigSnap.isFutureKumoBearish &&
                stratSnap.isFutureKumoBearish
        }

        // ۷. لایهٔ ساختاری ۷ (TSG 4-Step Quick Profit): کلوز بیرون کومو + کراس TK + چیکو بالای/زیر کومو ۲۶ دوره قبل
        val book7 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                trigSnap.tenkan >= trigSnap.kijun &&
                trigSnap.chikouAboveCloud26
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.tenkan <= trigSnap.kijun &&
                trigSnap.chikouBelowCloud26
        }

        // ۸. لایهٔ ساختاری ۸ (Koonitz 5 Golden Rules + DMI/MACD + EMA34): ۵ قانون کونیتز با ADX/DMI یا MACD
        val book8 = if (isLong) {
            trigSnap.close > trigSnap.kijun &&
                trigSnap.close > trigSnap.kumoTop &&
                trigSnap.chikouAboveCloud26 &&
                trigSnap.close >= trigSnap.ema34 &&
                (trigSnap.isDmiBullish || trigSnap.isMacdBullish)
        } else {
            trigSnap.close < trigSnap.kijun &&
                trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.chikouBelowCloud26 &&
                trigSnap.close <= trigSnap.ema34 &&
                (trigSnap.isDmiBearish || trigSnap.isMacdBearish)
        }

        // ۹. لایهٔ ساختاری ۹ (Koonitz Triple Screen 7-22-44 + EMA 13/34/48): هم‌جهتی ۳ تایم‌فریم و ایچیموکوی سریع
        val macroTripleOk = if (macroSnap == null) {
            true
        } else if (isLong) {
            !macroSnap.isPriceBelowKumo && macroSnap.ema13 >= macroSnap.ema48 - macroSnap.atr14 * 0.25
        } else {
            !macroSnap.isPriceAboveKumo && macroSnap.ema13 <= macroSnap.ema48 + macroSnap.atr14 * 0.25
        }
        val book9 = macroTripleOk && if (isLong) {
            trigSnap.tenkan7 >= trigSnap.kijun22 &&
                trigSnap.close > trigSnap.kumoTop72244 &&
                trigSnap.ema13 >= trigSnap.ema34 &&
                stratSnap.ema13 >= stratSnap.ema34 - stratSnap.atr14 * 0.08
        } else {
            trigSnap.tenkan7 <= trigSnap.kijun22 &&
                trigSnap.close < trigSnap.kumoBottom72244 &&
                trigSnap.ema13 <= trigSnap.ema34 &&
                stratSnap.ema13 <= stratSnap.ema34 + stratSnap.atr14 * 0.08
        }
```

</details>

**وزن‌دهی بیزین و R:R پله‌ای اجماع** — خطوط ۳۲۸۵ تا ۳۳۴۸

```kotlin
        val flags = listOf(book1, book2, book3, book4, book5, book6, book7, book8, book9)
        val count = flags.count { it }

        // Adaptive Bayesian Log-Odds Pooling with Regime Weighting (Squeeze vs Trend vs Pullback)
        val isSqueezeRegime = trigSnap.isBollingerSqueeze
        val isStrongAdxRegime = trigSnap.adx14 >= 22.0
        var logOdds = 0.15 // Base prior (~53.7%)
        if (book1) logOdds += if (isSqueezeRegime) 0.42 else 0.26
        if (book2) logOdds += 0.34
        if (book3) logOdds += 0.36
        if (book4) logOdds += if (trigSnap.kellySignalCount >= 4) 0.38 else 0.28
        if (book5) logOdds += 0.30
        if (book6) logOdds += 0.26
        if (book7) logOdds += 0.28
        if (book8) logOdds += if (isStrongAdxRegime) 0.40 else 0.30
        if (book9) logOdds += 0.36
        if (!book5 && trigSnap.isKijunFlat) logOdds -= 0.35

        val bayesianProb = (100.0 / (1.0 + kotlin.math.exp(-logOdds))).coerceIn(18.0, 97.5)

        // Composite Sniper Anchor (Weighted median of Tenkan9, Tenkan10, Tenkan7, EMA21)
        val anchors = listOf(trigSnap.tenkan, trigSnap.tenkan10, trigSnap.tenkan7, trigSnap.ema21).sorted()
        val compositeAnchor = round2((anchors[1] + anchors[2]) / 2.0)

        // Composite Fortress Stop-Loss: 5-Bar Fractal + Kijun26 + Kijun30 + EMA34 inside [0.80 ATR .. 2.10 ATR]
        val compositeStop = computeOptimalStructuralStopLoss(
            symbol = BTC_SYMBOL,
            isLong = isLong,
            referenceEntry = trigSnap.close,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            wave = null,
            slBufferAtr = 0.25,
            triggerInterval = Interval.M5,
            activeModel = BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER,
        )

        val dynamicRr = when {
            count >= 8 -> 2.85
            count >= 7 -> 2.70
            count >= 6 -> 2.55
            count >= 5 -> 2.40
            else -> 2.25
        }.coerceIn(1.50, 3.00)

        val badges = buildList {
            if (book1) add("لایهٔ ۱ (فشردگی بولینگر)")
            if (book2) add("لایهٔ ۲ (EMA21 + RSI7 + فرکتال)")
            if (book3) add("لایهٔ ۳ (ایچیموکو ۱۰/۳۰/۶۰)")
            if (book4) add("لایهٔ ۴ (معیار کلی + RSI)")
            if (book5) add("لایهٔ ۵ (فیلتر چیکو)")
            if (book6) add("لایهٔ ۶ (سطح کلیدی + کومو تویست)")
            if (book7) add("لایهٔ ۷ (۴ گام سریع)")
            if (book8) add("لایهٔ ۸ (DMI/MACD + EMA34)")
            if (book9) add("لایهٔ ۹ (سه‌صفحهٔ ۷‑۲۲‑۴۴)")
        }

        val summary = String.format(
            Locale.US,
            "اجماع %d/9 لایهٔ ساختاری · احتمال برد بیزین %.1f%% · تارگت بهینه 1:%.2f",
            count,
            bayesianProb,
            dynamicRr,
        )
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

**دروازه‌بندی مکتبی بیس (۳.۹.۵۷)** — خطوط ۴۵۶۵ تا ۴۵۷۷

```kotlin
        // ── دروازه‌بندی مکتبی بیس (۳.۹.۵۷) ────────────────────────────────────
        // پیش از این تنها راه ورود، «هر ۱۷ شرط هم‌زمان» بود؛ با احتمال تجربی هر شرط
        // (۵۵٪ تا ۸۲٪) حاصل‌ضرب عملاً صفر می‌شود و در ۸۸ روز یک معامله می‌دهد. در کتاب‌های
        // مرجع (پاتل، تاتل، هوسودا، پلویل) هیچ‌جا «۱۶ تأیید هم‌زمان» شرط ورود نیست؛ آن‌ها سه
        // لایه دارند و هر لایه باید سالم باشد:
        //   • لایهٔ ۱ — رژیم و جهت (شرط ۱ کومو، ۳ شیب TK، ۴ ابر آینده، ۶ چیکو): **وتوی سخت**.
        //   • لایهٔ ۲ — تعادل و ریسک (۸ کش کیجون، ۹ جاذبه/دیوار، ۱۳ ضد رِنج، ۱۵ فاز روند،
        //     ۵ ضخامت ابر، ۱۴ شدو): باید امتیاز لازم را بیاورد؛ اجازه دارد یک ضعف جزئی داشته باشد.
        //   • لایهٔ ۳ — ماشه و هندسهٔ ورود (۱۱ کندل ماشه + حجم، ۱۶ اسنایپر، ۱۷ اقتصاد اجرا،
        //     ۱۰ ساختار موج، ۱۲ دالان R:R، ۲ سانیاکو، ۷ شکست چیکو): همین‌جا معامله متولد می‌شود.
        // نگه‌داشتن دستهٔ اول به‌صورت AND، همان چیزی است که «هیچ‌وقت در رِنج معامله نکن» را
        // تضمین می‌کند؛ شل‌کردن آن مجاز نیست. بقیه با آستانهٔ امتیاز وزن‌دار کنترل می‌شوند.
```
**گیت کیجون (فقط استراتژی ۱۶): کراس ۹/۲۶ + خطوط پیش‌بینی ۸/۲۵، ۷/۲۴، ۶/۲۳ + وضعیت ابر** — خطوط ۴۵۷۸ تا ۴۶۰۵

```kotlin
        // ── گیت کیجون (فقط استراتژی ۱۶): کراس ۹/۲۶ + خطوط پیش‌بینی ۸/۲۵، ۷/۲۴، ۶/۲۳ + وضعیت ابر ──
        val kijunGate = if (KIJUN_LAYER_IN_STRATEGY_16_ENABLED && activeModel.isNeoWaveElliottStrategy) {
            evaluateKijunPredictiveGate(
                candlesInput = alignedStrategyCandles,
                isLong = isLong,
                strategySnapshot = stratSnap,
            )
        } else {
            null
        }
        val kijunGateRaw = (kijunGate?.passed ?: true) && (kijunStopRule?.blocked != true)
        // در «حالت حداکثر اجرا» این گیت نمایشی است: شرط‌های خود استراتژی تصمیم می‌گیرند.
        val kijunGatePassed = kijunGateRaw || effectiveConfig.allowMaximumExecution
        val firstFifteenPassed = modelCoreConditions.filter { it.index != 16 }.all { it.passed }
        val triggerFired = effectiveTriggerType != StrategyTriggerType.NONE
        val passedCount = conditions.count { it.passed }
        val phaseBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 8.0
            TrendPhase.CORE_EXPANSION -> 5.0
            TrendPhase.LATE_EXHAUSTION -> -15.0
            TrendPhase.CHOP_RANGE -> -25.0
        }
        val erBonus = ((erStrat + erTrig) * 5.0).coerceIn(0.0, 10.0)
        val rrBonus = (rr * 2.0).coerceIn(0.0, 8.0)
        val sniperBonus = (sniperEntryScore * 0.06).coerceIn(0.0, 6.0)
        val confidence = round2(((passedCount.toDouble() / conditions.size.toDouble()) * 84.0 + if (triggerFired) 16.0 else 0.0).coerceIn(0.0, 100.0))
        val coreTrendScore = round2((confidence * 0.74 + phaseBonus + erBonus + rrBonus + sniperBonus).coerceIn(0.0, 100.0))
```
**لایهٔ اختیاری نئوویو (NeoWave Gate)** — خطوط ۴۶۰۶ تا ۴۶۷۵

```kotlin
        // ── لایهٔ اختیاری نئوویو (NeoWave Gate) ────────────────────────────────
        // قانون صفر-اثر: وقتی `neoWaveEnabled = false` باشد هیچ محاسبه‌ای انجام نمی‌شود و
        // خروجی این تابع بیت‌به‌بیت مثل نسخهٔ قبل از نئوویو می‌ماند.
        // نئوویو–الیوت فقط و فقط در استراتژی جایگاه ۱۶ ادغام شده است و همیشه در همان استراتژی
        // روشن است (کلید کاربر ندارد). برای هیچ روش دیگری محاسبه‌ای انجام نمی‌شود.
        val neoWaveOn = activeModel.isNeoWaveElliottStrategy
        // دامنهٔ زیگزاگ داینامیک: با رژیم نوسان همان نماد/تایم‌فریم مقیاس می‌گیرد (قاعدهٔ درجه/دامنهٔ نیلی).
        val neoWaveAmplitude = if (neoWaveOn) {
            NeoWaveEngine.dynamicAmplitudeAtr(alignedStrategyCandles, effectiveConfig.neoWaveAmplitudeAtr)
        } else {
            effectiveConfig.neoWaveAmplitudeAtr
        }
        val neoWaveVerdict = if (neoWaveOn) {
            NeoWaveEngine.analyze(candles = alignedStrategyCandles, amplitudeAtr = neoWaveAmplitude)
        } else {
            null
        }
        // ساختار درجهٔ بالاتر (تایم‌فریم ماکرو): بر درجهٔ پایین‌تر حکومت می‌کند.
        val neoWaveHigherVerdict = if (neoWaveOn) {
            NeoWaveEngine.analyze(candles = alignedMacroCandles, amplitudeAtr = neoWaveAmplitude)
        } else {
            null
        }
        val neoWaveMtf = if (neoWaveOn) {
            NeoWaveEngine.mtfConfluence(neoWaveVerdict, neoWaveHigherVerdict, isLong)
        } else {
            NeoWaveEngine.MtfConfluence.NEUTRAL
        }
        // آستانه‌ها: اگر تنظیمات به هوش عصبی سپرده شده باشد، همان مقادیر آموخته‌شده اعمال می‌شوند.
        val neoWaveRulesThreshold = effectiveConfig.resolvedNeoWaveRules(isCrypto = false)
        val neoWaveConfidenceThreshold = effectiveConfig.resolvedNeoWaveConfidence(isCrypto = false)
        val neoWaveGate = if (neoWaveOn) {
            NeoWaveEngine.gate(
                verdict = neoWaveVerdict,
                isLong = isLong,
                minRulesPassed = neoWaveRulesThreshold,
                minConfidencePct = neoWaveConfidenceThreshold,
                ruleWeights = effectiveConfig.neoWaveWeightsMap(),
            )
        } else {
            NeoWaveEngine.NeoWaveGate.OFF
        }
        val neoWaveTotalDelta = neoWaveGate.confidenceDelta + neoWaveMtf.deltaConfidence
        val gatedConfidence = if (neoWaveOn) {
            round2((confidence + neoWaveTotalDelta).coerceIn(0.0, 100.0))
        } else {
            confidence
        }
        val gatedTrendScore = if (neoWaveOn) {
            round2((coreTrendScore + neoWaveTotalDelta * 0.5).coerceIn(0.0, 100.0))
        } else {
            coreTrendScore
        }

        val customWatchSetup = when {
            isBtc52Model -> closedTriggerBars.size >= 53 && (long52Context || short52Context)
            isParsaModel -> parsaFlatOk && (parsaSpanATurnOk || parsaCrossAge <= 8)
            isHiddenCrossModel -> hcDirectionOk && hcCrossExistsOk
            else -> false
        }
        val sniperArmedPendingRetest = !isBtc52Model && !isParsaModel && !isHiddenCrossModel &&
            firstFifteenPassed && triggerFired && !cond16Passed

        // امتیاز مکتبی: کدام شرط‌ها «وتوی سخت»‌اند و کدام‌ها فقط وزن دارند.
        val bookGate = bookConfluenceGate(
            conditions = baseSixteenConditions,
            isBaseModel = !isBtc52Model && !isParsaModel && !isHiddenCrossModel && !activeModel.isDrivePdfStrategy,
            baseConfig = effectiveConfig,
        )
```
**پیمان: شمارش موج باید **کامل و هم‌جهت** باشد** — خطوط ۴۶۷۶ تا ۴۷۱۳

```kotlin
        // ── پیمان: شمارش موج باید **کامل و هم‌جهت** باشد ────────────────────────
        // پیش از این، معامله با شمارش نیمه‌کاره هم باز می‌شد: هشت شرط الیوت
        // (جهت موج، نقطهٔ S، فضای هدف، چرخهٔ زمان، R:R، ER14، شدو، اسنایپر) پاس
        // می‌شدند ولی هیچ‌کدام نمی‌پرسید «آیا شمارش موج تمام شده است؟». در متد
        // نئوویو، ورود پیش از تکمیل مونوویو یعنی ورود به وادی حدس — همان چیزی که
        // بازده این استراتژی را منفی می‌کرد.
        val peymanWaveConfirmed = !activeModel.isNeoWaveElliottStrategy || (
            neoWaveVerdict != null &&
                neoWaveVerdict.isComplete &&
                neoWaveVerdict.isAlignedWith(isLong)
            )
        // وتوی نئوویو فقط وقتی ورود را می‌بندد که خودِ استراتژیِ فعال نئوویو باشد (یا حالت
        // حداکثر اجرا خاموش باشد). پیش از این، یک لایهٔ نمایشی می‌توانست هر ورودی را ببندد.
        val neoWaveVetoActive = (neoWaveGate.veto || (neoWaveOn && neoWaveMtf.veto)) &&
            (activeModel.isNeoWaveElliottStrategy || !effectiveConfig.allowMaximumExecution)
        val baseReadiness = when {
            !peymanWaveConfirmed -> StrategyReadinessState.BLOCKED
            // مسیر مستقل «فشردگی → انبساط»؛ همهٔ وتوها و اقتصاد اجرا از پیش سنجیده شده‌اند.
            compressionEntry && kijunGatePassed && isLong -> StrategyReadinessState.SIGNAL_BUY
            compressionEntry && kijunGatePassed && !isLong -> StrategyReadinessState.SIGNAL_SELL
            neoWaveVetoActive -> StrategyReadinessState.BLOCKED
            // مسیر کلاسیک: هر ۱۷ شرط هم‌زمان (سخت‌گیرترین حالت، هنوز معتبر است).
            allSixteenPassed && kijunGatePassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            allSixteenPassed && kijunGatePassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            allSixteenPassed && !kijunGatePassed -> StrategyReadinessState.BLOCKED
            // مسیر مکتبی بیس: وتوی سخت‌ها پاس + امتیاز هم‌گرایی کافی + ماشه.
            bookGate.fires && kijunGatePassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            bookGate.fires && kijunGatePassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            // «آماده» یعنی نزدیکِ ورود، نه «بهتر از نصف». اندازه‌گیری روی سری مصنوعی نشان داد
            // آستانهٔ ۶۶٪ در ۴۵٪ کندل‌ها (و شمارش ≥۱۱ شرط در بیشتر کندل‌ها) برقرار می‌شد و
            // برچسب «آماده» عملاً همه‌جا بود؛ پس همان آستانهٔ شلیک (۸۰٪) با یک پله پایین‌تر
            // استفاده می‌شود تا فقط ستاپ‌های واقعاً نزدیک دیده شوند.
            customWatchSetup || sniperArmedPendingRetest || bookGate.watch ||
                (!isBtc52Model && !isParsaModel && !isHiddenCrossModel &&
                    passedCount >= BOOK_WATCH_MIN_CONDITIONS) -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }
```
**مسیر ورود رمزارز بر پایهٔ نئوویو** — خطوط ۴۷۱۴ تا ۴۷۳۰

```kotlin
        // ── مسیر ورود رمزارز بر پایهٔ نئوویو ─────────────────────────────────
        // شرط عمومی (هر ۱۷ شرط هم‌زمان) در رمزارز می‌تواند روزها برقرار نشود. در این مسیر
        // الگوی کامل، تأییدشده و هم‌جهتِ نئوویو ماشهٔ ورود است؛ کنترل‌های ریسک دست‌نخورده‌اند
        // (R:R ≥ ۱.۵، پلن معتبر، بدون وتو، بدون بازار بسته).
        val isCryptoClass = AssetClass.of(normalizedSymbol) == AssetClass.CRYPTO
        val cryptoEntryDecision = NeoWaveEngine.cryptoEntryDecision(
            enabled = neoWaveOn && effectiveConfig.neoWaveCryptoEntryEnabled &&
                !neoWaveGate.veto && !neoWaveMtf.veto,
            isCrypto = isCryptoClass,
            verdict = neoWaveVerdict,
            isLong = isLong,
            minRulesPassed = effectiveConfig.resolvedNeoWaveRules(isCrypto = true),
            minConfidencePct = effectiveConfig.resolvedNeoWaveConfidence(isCrypto = true),
            riskReward = rr,
            hasValidPlan = finalStopLoss != null && finalTakeProfit != null && sniperOptimal != null &&
                rr >= 1.5,
        )
```
**موتور «پیمان کریپتو حرفه‌ای»** — خطوط ۴۷۳۱ تا ۴۷۶۷

```kotlin
        // ── موتور «پیمان کریپتو حرفه‌ای» ─────────────────────────────────────
        // فقط برای استراتژی ۱۶ و فقط روی رمزارز. در حالت DISABLED هیچ محاسبه‌ای
        // انجام نمی‌شود (هزینهٔ صفر و رفتار قبلی دست‌نخورده). در حالت SHADOW تصمیم
        // کامل ثبت می‌شود ولی معامله‌ای باز نمی‌شود؛ فقط PAPER_ENABLED اجازهٔ ورود دارد.
        val isPeymanStrategyModel = activeModel == BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER
        val peymanDecision: PeymanCryptoDecision? = if (
            isPeymanStrategyModel && isCryptoClass && effectiveConfig.peymanCrypto.isActive
        ) {
            runCatching {
                PeymanCryptoEngine.decide(
                    PeymanCryptoInput(
                        symbol = normalizedSymbol,
                        macroBars = alignedMacroCandles,
                        strategyBars = alignedStrategyCandles,
                        triggerBars = alignedTriggerCandles,
                        openPositions = emptyList(),
                        config = effectiveConfig.peymanCrypto,
                        nowMillis = nowMillis,
                        triggerInterval = config.triggerInterval,
                    ),
                )
            }.getOrNull()
        } else {
            null
        }

        val cryptoNeoWaveEntry = if (peymanDecision != null) {
            // موتور جدید جانشین مسیر نئوویوِ خام است، نه یک fallback دورزننده.
            peymanDecision.isTradeAllowed &&
                peymanDecision.action == (if (isLong) SignalAction.BUY else SignalAction.SELL) &&
                baseReadiness != StrategyReadinessState.SIGNAL_BUY &&
                baseReadiness != StrategyReadinessState.SIGNAL_SELL
        } else {
            cryptoEntryDecision.eligible &&
                baseReadiness != StrategyReadinessState.SIGNAL_BUY &&
                baseReadiness != StrategyReadinessState.SIGNAL_SELL
        }
```
**سیاست «فقط پیمان روی رمزارز»** — خطوط ۴۷۶۸ تا ۵۶۳۹
<details><summary>کد کامل (۸۷۱ خط)</summary>

```kotlin
        // ── سیاست «فقط پیمان روی رمزارز» ─────────────────────────────────────
        // تنها مسیر ورودِ رمزارز، تصمیم موتور پیمان است. اگر پیمان ورود را مجاز نداند
        // (یا خاموش باشد) هیچ استراتژی دیگری روی رمزارز معامله نمی‌کند.
        val peymanPolicyActive = isCryptoClass && effectiveConfig.cryptoSymbolsOnlyPeyman
        val peymanAllowsEntry = peymanDecision?.isTradeAllowed == true
        val blockedByPeymanPolicy = peymanPolicyActive && !peymanAllowsEntry

        val readiness = when {
            cryptoNeoWaveEntry && isLong -> StrategyReadinessState.SIGNAL_BUY
            cryptoNeoWaveEntry -> StrategyReadinessState.SIGNAL_SELL
            blockedByPeymanPolicy -> StrategyReadinessState.WATCH_SETUP
            else -> baseReadiness
        }

        val finalAction = if (blockedByPeymanPolicy) {
            SignalAction.NO_TRADE
        } else if (neoWaveVetoActive) {
            SignalAction.NO_TRADE
        } else if (!kijunGatePassed && !cryptoNeoWaveEntry) {
            // استراتژی ۱۶ فقط با تأیید کراس ۹/۲۶، خطوط پیش‌بینی و ابر معامله می‌کند.
            SignalAction.NO_TRADE
        } else if (cryptoNeoWaveEntry) {
            if (isLong) SignalAction.BUY else SignalAction.SELL
        } else {
            when (readiness) {
                StrategyReadinessState.SIGNAL_BUY -> SignalAction.BUY
                StrategyReadinessState.SIGNAL_SELL -> SignalAction.SELL
                else -> if (sniperArmedPendingRetest) {
                    if (isLong) SignalAction.BUY else SignalAction.SELL
                } else {
                    SignalAction.NO_TRADE
                }
            }
        }

        val tfTag = "[تایم ${config.triggerInterval.label}]"
        val baseSummaryFa = when {
            readiness == StrategyReadinessState.SIGNAL_BUY -> when {
                isBtc52Model -> "$tfTag 🟠 خرید BTC بر پایهٔ شکست/بازآزمایی ۵۲ کندلی · بدون الگوی سه کندل آخر · R:R=1:${round2(rr)}"
                isParsaModel -> "$tfTag 🟢 خرید پارسا · فلت SSB ${trigSnap.senkouSpanBFlatBars} کندل + چرخش Senkou A + کراس TK · R:R=1:${round2(rr)}"
                isHiddenCrossModel -> "$tfTag 🟢 خرید هیدن کراس · ابر مخفی ${trigSnap.hiddenCloudBottom}..${trigSnap.hiddenCloudTop} + کراس برابری ${trigSnap.equalCrossLevel} · تاج ${trigSnap.crownLevel} · R:R=1:${round2(rr)}"
                else -> "$tfTag 🎯 خرید نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            }
            readiness == StrategyReadinessState.SIGNAL_SELL -> when {
                isBtc52Model -> "$tfTag 🟠 فروش BTC بر پایهٔ شکست/بازآزمایی ۵۲ کندلی · بدون الگوی سه کندل آخر · R:R=1:${round2(rr)}"
                isParsaModel -> "$tfTag 🔴 فروش پارسا · فلت SSB ${trigSnap.senkouSpanBFlatBars} کندل + چرخش Senkou A + کراس TK · R:R=1:${round2(rr)}"
                isHiddenCrossModel -> "$tfTag 🔴 فروش هیدن کراس · ابر مخفی ${trigSnap.hiddenCloudBottom}..${trigSnap.hiddenCloudTop} + کراس برابری ${trigSnap.equalCrossLevel} · تاج ${trigSnap.crownLevel} · R:R=1:${round2(rr)}"
                else -> "$tfTag 🎯 فروش نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            }
            sniperArmedPendingRetest ->
                "$tfTag 🎯 مسلح برای نقطه‌زنی ($normalizedSymbol · $effectiveTitle): در کمین لمس $sniperOptimal"
            readiness == StrategyReadinessState.WATCH_SETUP -> when {
                isBtc52Model -> "$tfTag ⏳ ساختار ۵۲ کندلی هم‌جهت است؛ منتظر شکست بسته‌شده/بازآزمایی و تأیید حجم/چیکو (${passedCount}/${conditions.size})"
                isParsaModel -> "$tfTag ⏳ SSB ${trigSnap.senkouSpanBFlatBars} کندل تخت؛ منتظر چرخش Senkou A، کراس TK و تأیید شارپ (${passedCount}/${conditions.size})"
                isHiddenCrossModel -> "$tfTag ⏳ ابر مخفی ${trigSnap.hiddenCloudThicknessAtr} ATR؛ منتظر کراس برابری تازه، هم‌جهتی و تأیید آنالوگ (${passedCount}/${conditions.size})"
                else -> "$tfTag ⏳ $passedCount/${conditions.size} شرط (${trendPhase.badgeFa})؛ منتظر ماشه در ${config.triggerInterval.label}"
            }
            readiness == StrategyReadinessState.BLOCKED -> {
                if (!kijunGatePassed) {
                    val why = when {
                        kijunStopRule?.blocked == true -> kijunStopRule.noteFa
                        kijunGate != null -> kijunGate.summaryFa
                        else -> ""
                    }
                    "$tfTag 🛑 گیت کیجون اجازه نداد · $why"
                } else {
                    val firstFailed = modelCoreConditions.firstOrNull { !it.passed } ?: conditions.firstOrNull { !it.passed }
                    "$tfTag 🛑 $passedCount/${conditions.size} شرط · مانع: ${firstFailed?.titleFa ?: ""}"
                }
            }
            else -> "$tfTag در حال دریافت کندل‌های $normalizedSymbol..."
        }
        val neoWaveSuffix = when {
            !neoWaveOn || neoWaveGate.noteFa.isBlank() -> ""
            neoWaveMtf.noteFa.isBlank() -> " · ${neoWaveGate.noteFa}"
            else -> " · ${neoWaveGate.noteFa} · ${neoWaveMtf.noteFa}"
        }
        val summaryFa = when {
            neoWaveVetoActive ->
                "$tfTag ⛔ ورود وتو شد · $normalizedSymbol · $effectiveTitle · ${neoWaveGate.noteFa}"
            cryptoNeoWaveEntry ->
                "$tfTag 🌊 ورود رمزارز بر پایهٔ نئوویو · $normalizedSymbol · $effectiveTitle · " +
                    "${cryptoEntryDecision.reasonFa} · R:R ۱:${round2(rr)}"
            else -> baseSummaryFa + neoWaveSuffix
        }
        val entryPathFa = when {
            isBtc52Model -> "روش مستقل بیت‌کوین با زمینهٔ ۵۲ کندل بسته؛ سه کندل آخر الگوی تصمیم نیستند؛ یادگیری عصبی خاموش"
            isParsaModel -> "پارسا: SSB/خط دورهٔ ۵۲ تخت ≥۱۸ کندل + چرخش Senkou A + کراس TK؛ شیب/فاصله، چیکو و حرکت شارپ سنجیده شد؛ یادگیری عصبی خاموش"
            isHiddenCrossModel -> "هیدن کراس: جهت از ابر مخفی (نوار تنکان/کیجون) و هدف از کراس برابری (تاج نصف‌اندازه)؛ " +
                "جست‌وجوی آنالوگ تاریخی با ${trigSnap.hiddenCloudAnalogueSamples} نمونه و هم‌جهتی " +
                "${(trigSnap.hiddenCloudAnalogueAgreement * 100).toInt()}٪؛ یادگیری عصبی خاموش"
            peymanDecision != null ->
                "🌊 پیمان کریپتو (${peymanDecision.mode.labelFa}): ${peymanDecision.summaryFa}"
            cryptoNeoWaveEntry -> "🌊 مسیر نئوویو (کریپتو): ${cryptoEntryDecision.reasonFa}"
            neoWaveOn && isCryptoClass && neoWaveVerdict != null ->
                "🌊 کریپتو: ${cryptoEntryDecision.reasonFa}"
            else -> ""
        }

        // شرایط نئوویو فقط برای نمایش به چک‌لیست اضافه می‌شوند (در محاسبهٔ امتیاز دخالت ندارند).
        val displayConditions = if (neoWaveOn && neoWaveVerdict != null) {
            conditions + neoWaveVerdict.rules.map { rule ->
                StrategyConditionCheck(
                    index = 16 + rule.index,
                    titleFa = "🌊 نئوویو ${rule.index}. ${rule.titleFa}",
                    passed = rule.passed,
                    detailFa = rule.detailFa,
                )
            }
        } else {
            conditions
        }

        return StrategyEvaluationReport(
            symbol = normalizedSymbol,
            strategyId = effectiveId,
            strategyTitleFa = effectiveTitle,
            strategyBadgeFa = activeModel.badgeFa,
            macroInterval = config.macroInterval,
            strategyInterval = config.strategyInterval,
            triggerInterval = config.triggerInterval,
            readiness = readiness,
            action = finalAction,
            triggerType = effectiveTriggerType,
            compressionExpansion = compressionAnalysis,
            peymanCrypto = peymanDecision,
            confidence = gatedConfidence,
            livePrice = round2(livePrice),
            suggestedEntry = sniperOptimal,
            suggestedStopLoss = finalStopLoss,
            targetV = wave?.targetV,
            targetN = wave?.targetN,
            targetE = wave?.targetE,
            primaryTakeProfit = finalTakeProfit,
            riskReward = rr,
            macroSnapshot = macroSnap,
            strategySnapshot = stratSnap,
            triggerSnapshot = trigSnap,
            waveProjection = wave,
            conditions = displayConditions,
            summaryFa = summaryFa,
            triggerBarTime = trigSnap.time,
            evaluatedAt = nowMillis,
            trendPhase = trendPhase,
            coreTrendScore = gatedTrendScore,
            sniperOptimalEntry = sniperOptimal,
            sniperZoneLow = sniperLow,
            sniperZoneHigh = sniperHigh,
            sniperEntryScore = sniperEntryScore,
            isSniperArmedPendingRetest = sniperArmedPendingRetest,
            bookGateNoteFa = bookGate.noteFa,
            bookGateScorePct = bookGate.totalScorePct,
            optimalRiskReward = effectiveOptimalRr,
            rrBreakdown = rrBreakdownObj,
            planStopPct = guardedPlan.stopPct,
            planTargetPct = guardedPlan.targetPct,
            planRecommendedLeverage = guardedPlan.recommendedLeverage,
            planVerdictFa = guardedPlan.verdict.badgeFa,
            planGuardNoteFa = guardedPlan.summaryFa(),
            neoWaveBadgeFa = if (neoWaveOn) neoWaveGate.noteFa else "",
            neoWaveVeto = neoWaveGate.veto,
            neoWavePatternId = if (neoWaveOn) neoWaveVerdict?.pattern?.name.orEmpty() else "",
            neoWaveRulesPassed = if (neoWaveOn) neoWaveVerdict?.rulesPassed ?: 0 else 0,
            neoWaveRulesTotal = if (neoWaveOn) neoWaveVerdict?.rulesTotal ?: 0 else 0,
            neoWaveConfidencePct = if (neoWaveOn) neoWaveVerdict?.confidencePct ?: 0.0 else 0.0,
            neoWaveFailedRuleIds = if (neoWaveOn) {
                neoWaveVerdict?.rules?.filter { !it.passed }?.map { it.index }.orEmpty()
            } else {
                emptyList()
            },
            neoWaveMtfFa = if (neoWaveOn) neoWaveMtf.noteFa else "",
            neoWaveAmplitudeAtr = if (neoWaveOn) neoWaveAmplitude else 0.0,
            cryptoNeoWaveEntry = cryptoNeoWaveEntry,
            entryPathFa = entryPathFa,
            kijunGateFa = kijunGate?.summaryFa.orEmpty(),
            kijunStopRuleFa = kijunStopRule?.noteFa.orEmpty(),
        )
    }

    /**
     * ارزیابی هم‌زمان تمام استراتژی‌های فعال روی هر دو تایم‌فریم ورود `15m` (`4H -> 1H -> 15m`) و `5m` (`1H -> 15m -> 5m`)
     * با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m`/`5m` برای جلوگیری از مصرف اضافه اینترنت.
     */
    fun evaluateDualTimeframeStrategy(
        activeModel: BitcoinStrategyModel,
        config: BaseStrategyConfig,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        livePriceOverride: Double? = null,
        customTitleOverride: String? = null,
        customIdOverride: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        symbolInput: String = BTC_SYMBOL,
        runAllSimultaneously: Boolean = true,
        enabledStrategyIds: Set<String> = BitcoinStrategyModel.activeStrategyIds,
        customStrategies: List<CustomStrategySlot> = emptyList(),
    ): DualTimeframeSymbolReport {
        val config15m = config.copy(
            macroIntervalLabel = Interval.H4.label,
            strategyIntervalLabel = Interval.H1.label,
            triggerIntervalLabel = Interval.M15.label,
        )
        val config5m = config.copy(
            macroIntervalLabel = Interval.H1.label,
            strategyIntervalLabel = Interval.M15.label,
            triggerIntervalLabel = Interval.M5.label,
        )

        val map15m = LinkedHashMap<String, StrategyEvaluationReport>()
        val map5m = LinkedHashMap<String, StrategyEvaluationReport>()

        if (runAllSimultaneously) {
            // استراتژی‌های موقتاً خاموش (فعلاً جایگاه ۱۶) حتی اگر در تنظیمات روشن باشند
            // ارزیابی نمی‌شوند؛ شناسه و آموخته‌هایشان حفظ می‌شود تا بعداً از صفر شروع نشوند.
            val activeBuiltIns = BitcoinStrategyModel.entries
                .filter { it.id in enabledStrategyIds && !it.isTemporarilyDisabled }
                .ifEmpty {
                    BitcoinStrategyModel.entries.filterNot {
                        it.isTemporarilyDisabled || it.requiresExplicitOptIn
                    }
                }
            for (model in activeBuiltIns) {
                map15m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config15m,
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config5m,
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
            for (slot in customStrategies.filter { it.id in enabledStrategyIds }) {
                val baseMod = BitcoinStrategyModel.fromId(slot.baseModelId)
                map15m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H4.label,
                        strategyIntervalLabel = Interval.H1.label,
                        triggerIntervalLabel = Interval.M15.label,
                    ),
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H1.label,
                        strategyIntervalLabel = Interval.M15.label,
                        triggerIntervalLabel = Interval.M5.label,
                    ),
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
        }

        val focusedId = customIdOverride ?: activeModel.id
        val rep15m = map15m[focusedId]
            ?: map15m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config15m,
                macroCandles = candles4h,
                strategyCandles = candles1h,
                triggerCandles = candles15m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map15m[it.strategyId] = it }

        val rep5m = map5m[focusedId]
            ?: map5m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config5m,
                macroCandles = candles1h,
                strategyCandles = candles15m,
                triggerCandles = candles5m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map5m[it.strategyId] = it }

        val best15m = map15m.values.firstOrNull { it.isActionable } ?: rep15m
        val best5m = map5m.values.firstOrNull { it.isActionable } ?: rep5m

        return DualTimeframeSymbolReport(
            symbol = best15m.symbol,
            report15m = best15m,
            report5m = best5m,
            reports15mByStrategy = map15m,
            reports5mByStrategy = map5m,
        )
    }

    /**
     * چک‌لیست اختصاصی استراتژی پیمان در نسخهٔ **الیوت–نئوویو**.
     *
     * بنا به درخواست کاربر فقط لایهٔ موجی باقی مانده است؛ شرط‌های اجماع چندلایه، گیت کیجون و
     * تأییدهای DMI/MACD/Kelly حذف شده‌اند. هشت شرط زیر مستقیماً از هندسهٔ موج و کیفیت ورود
     * ساخته می‌شوند و `confidence` موتور مثل قبل از نسبت شرط‌های پاس‌شده محاسبه می‌شود.
     */
    private fun buildElliottWaveConditions(
        isLong: Boolean,
        wave: HosodaWaveProjection?,
        selectedWaveLabel: String,
        rewardDistSniper: Double,
        cond12Passed: Boolean,
        cond12Detail: String,
        cond13Passed: Boolean,
        cond13Detail: String,
        cond14Passed: Boolean,
        cond14Detail: String,
        cond16Passed: Boolean,
        cond16Detail: String,
    ): List<StrategyConditionCheck> {
        val waveAligned = wave != null &&
            ((wave.direction == SignalAction.BUY) == isLong)
        val sPointHeld = wave?.sPointHeld == true
        val waveRoomOk = selectedWaveLabel.isNotBlank() && rewardDistSniper > 0.0
        val timeCycleClear = wave?.isNearTimeCycleChangeDay != true
        return listOf(
            StrategyConditionCheck(
                1,
                "۱. جهت موج الیوت هم‌سو با جهت معامله",
                waveAligned,
                if (wave == null) {
                    "شمارش موج هنوز کامل نیست"
                } else {
                    "جهت موج: " + if (wave.direction == SignalAction.BUY) "صعودی" else "نزولی"
                },
            ),
            StrategyConditionCheck(
                2,
                "۲. معتبر بودن نقطهٔ چرخش (S-Point) و شکسته‌نشدن آن",
                sPointHeld,
                if (wave == null) "نقطهٔ S در دسترس نیست" else "نقطهٔ S: ${round2(wave.pointC)}",
            ),
            StrategyConditionCheck(
                3,
                "۳. فضای هدف موجی (V/N/E) برای ادامهٔ حرکت",
                waveRoomOk,
                if (wave == null) "هدف موجی محاسبه نشده" else "هدف انتخابی: $selectedWaveLabel",
            ),
            StrategyConditionCheck(
                4,
                "۴. چرخهٔ زمانی هوسودا در نقطهٔ تغییر نباشد",
                timeCycleClear,
                if (wave == null) "چرخهٔ زمانی نامشخص" else "چرخهٔ نزدیک: ${wave.nearestTimeCycleNumber}",
            ),
            StrategyConditionCheck(
                5,
                "۵. عدد مناسب R:R (حداقل ۱:۱.۵) روی اهداف موجی",
                cond12Passed,
                cond12Detail,
            ),
            StrategyConditionCheck(
                6,
                "۶. کارایی روند کافمن (ER14) برای موج جهت‌دار",
                cond13Passed,
                cond13Detail,
            ),
            StrategyConditionCheck(
                7,
                "۷. نبود شدوی معکوس و فیک‌بریک‌اوت در موج جاری",
                cond14Passed,
                cond14Detail,
            ),
            StrategyConditionCheck(
                8,
                "۸. نقطه‌زنی ورود در ناحیهٔ موج (Sniper)",
                cond16Passed,
                cond16Detail,
            ),
        )
    }

    /**
     * تشخیص ۴ ماشه ورود برای شکار اصل روند از لحظه تولد (`EARLY_KATANA_GENESIS`) تا قلب موج (`PULLBACK / BREAKOUT / SANYAKU`)
     * استراتژی ۱۶ (پیمان) ماشهٔ اختصاصی ندارد و از همین ماشه‌های استاندارد استفاده می‌کند.
     */
    private fun detectEntryTrigger(
        direction: SignalAction,
        bars: List<Candle>,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        config: BaseStrategyConfig,
        activeModel: BitcoinStrategyModel = BitcoinStrategyModel.BASE_ICHIMOKU,
    ): StrategyTriggerType {
        if (bars.size < 5) return StrategyTriggerType.NONE
        val cur = bars.last()
        val prev1 = bars[bars.lastIndex - 1]
        val prev2 = bars[bars.lastIndex - 2]
        val prev3 = bars[bars.lastIndex - 3]

        val isLong = direction == SignalAction.BUY
        val breakoutBuf = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * config.breakoutBufferAtr
        } else {
            trigSnap.atr14 * 0.03
        }

        // ماشه‌های اختصاصی متود جامع ۹ لایه حذف شدند: استراتژی ۱۶ فقط لایهٔ الیوت را دارد و از
        // ماشه‌های استاندارد پایین استفاده می‌کند (و فعلاً غیرفعال است).

        if (isLong) {
            // Trigger 0: Early Katana Genesis (captures the very birth of the core trend right as TK compression releases)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close > cur.open &&
                    cur.close > max(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close > trigSnap.kumoTop &&
                    trigSnap.chikouBullishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Top (S-point) and close above Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.low <= max(trigSnap.tenkan, trigSnap.kijun) + trigSnap.atr14 * 0.35 &&
                        b.close >= trigSnap.kijun - trigSnap.atr14 * 0.20
                }
                val bullishCandleCloseAboveTenkan = cur.close > cur.open &&
                    cur.close >= trigSnap.tenkan &&
                    cur.close > trigSnap.kumoTop
                if (recentTouchedEquilibrium && bullishCandleCloseAboveTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Trigger 2: Validated Kumo / Kijun breakout with Chikou resistance peak clearance
            if (config.enableBreakoutTrigger) {
                val crossedUpRecently = (prev1.close <= trigSnap.kumoTop || prev2.close <= trigSnap.kumoTop || prev1.close <= trigSnap.kijun) &&
                    cur.close > max(trigSnap.kumoTop, trigSnap.kijun) + breakoutBuf
                val chikouClearedPeak = cur.close >= trigSnap.chikouResistancePeak + breakoutBuf
                if (crossedUpRecently && chikouClearedPeak && cur.close > cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBullishOrder && trigSnap.threeCandleBullishImpulse &&
                    cur.close >= max(prev1.high, prev2.high) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        } else {
            // Short Trigger 0: Early Katana Genesis (captures the very birth of a bearish core trend)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close < cur.open &&
                    cur.close < min(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close < trigSnap.kumoBottom &&
                    trigSnap.chikouBearishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Short Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Bottom and close below Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.high >= min(trigSnap.tenkan, trigSnap.kijun) - trigSnap.atr14 * 0.35 &&
                        b.close <= trigSnap.kijun + trigSnap.atr14 * 0.20
                }
                val bearishCandleCloseBelowTenkan = cur.close < cur.open &&
                    cur.close <= trigSnap.tenkan &&
                    cur.close < trigSnap.kumoBottom
                if (recentTouchedEquilibrium && bearishCandleCloseBelowTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Short Trigger 2: Validated Kumo / Kijun breakdown with Chikou support trough clearance
            if (config.enableBreakoutTrigger) {
                val crossedDownRecently = (prev1.close >= trigSnap.kumoBottom || prev2.close >= trigSnap.kumoBottom || prev1.close >= trigSnap.kijun) &&
                    cur.close < min(trigSnap.kumoBottom, trigSnap.kijun) - breakoutBuf
                val chikouClearedTrough = cur.close <= trigSnap.chikouSupportTrough - breakoutBuf
                if (crossedDownRecently && chikouClearedTrough && cur.close < cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBearishOrder && trigSnap.threeCandleBearishImpulse &&
                    cur.close <= min(prev1.low, prev2.low) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        }

        return StrategyTriggerType.NONE
    }

    /**
     * دروازهٔ هم‌گرایی مکتبی برای «استراتژی بیس» — جایگزین منطق «۱۶ تأیید هم‌زمان».
     *
     * ── چرا این تابع وجود دارد؟ ──────────────────────────────────────────────
     * با اندازه‌گیری روی ۵٬۵۲۱ ارزیابی (سری ۱۵ دقیقه‌ای، ۶٬۰۰۰ کندل) نرخ پاس هر شرط بین
     * ۵۵٪ و ۸۲٪ بود؛ حاصل‌ضرب ۱۶ عدد در این حدود، احتمال عملی صفر می‌دهد. نتیجه: در ۸۸ روز
     * یک معامله. این ریاضیات است، نه بدشانسی بازار.
     *
     * ── چرا این تفکیک «کتاب‌وفادار» است؟ ─────────────────────────────────────
     * هیچ‌کدام از چهار مکتب منبع (پاتل، تاتل، هوسودا، پلویل) «۱۶ تأیید هم‌زمان» را شرط ورود
     * نمی‌دانند. ساختار مشترکشان سه لایه است:
     *
     *   لایهٔ ۱ — رژیم و جهت: قیمت نسبت به کومو، ترتیب تنکان/کیجون و شیبشان، جهت ابر آینده،
     *              آزادی چیکو. در هر چهار مکتب این‌ها **تعریف روند** هستند، نه فیلتر؛ اگر
     *              روند تعریف نشود، هیچ معامله‌ای وجود ندارد. ⇒ **وتوی سخت** (AND).
     *
     *   لایهٔ ۲ — تعادل و ریسک: کش کیجون (پاتل)، جاذبهٔ SSB فلت و دیوار (پلویل/تاتل)، کارایی
     *              روند (فیلتر رِنج)، فاز موج (هوسودا)، ضخامت ابر آینده، شدوی معکوس. این‌ها
     *              «کیفیت» را می‌سنجند؛ در کتاب هم هیچ‌جا هر شش‌تا هم‌زمان لازم نیست. ⇒ **وزن‌دار**،
     *              با اجازهٔ یک ضعف جزئی.
     *
     *   لایهٔ ۳ — ماشه و هندسهٔ ورود: کندل ماشه + حجم، ناحیهٔ اسنایپر، ساختار موج N، دالان R:R،
     *              چیدمان سانیاکو، شکست چیکو. این‌ها همان «entry trigger» کتاب‌ها هستند و باید
     *              هم‌زمان با رژیم درست باشند. ⇒ **الزامی ولی با تحمل یک ضعف حاشیه‌ای**.
     *
     * آستانه‌ها از همان توزیع اندازه‌گیری‌شده آمده‌اند: هر لایه باید دست‌کم ۷۰٪ وزن خودش را
     * بیاورد و مجموع کل دست‌کم ۸۰٪. این اعداد با آزمون `BaseStrategyBookGateTest` قفل شده‌اند.
     */
    internal data class BookGateResult(
        val hardVetoPassed: Boolean,
        val fires: Boolean,
        val watch: Boolean,
        val totalScorePct: Double,
        val regimeScorePct: Double,
        val qualityScorePct: Double,
        val triggerScorePct: Double,
        val failedHard: List<String>,
        val bestMissing: List<String>,
        val noteFa: String,
    )

    /** وزن هر شرط در امتیاز مکتبی. وتوهای سخت با `hardVeto = true` علامت خورده‌اند. */
    private val BOOK_WEIGHTS: Map<Int, Double> = mapOf(
        // لایهٔ ۱ — رژیم و جهت (وتوی سخت)
        1 to 1.0,
        3 to 1.0,
        4 to 1.0,
        6 to 1.0,
        // لایهٔ ۲ — تعادل و ریسک
        8 to 1.0,
        9 to 1.0,
        13 to 1.0,
        15 to 1.0,
        5 to 0.6,
        14 to 0.6,
        // لایهٔ ۳ — ماشه و هندسهٔ ورود
        11 to 1.2,
        16 to 1.2,
        17 to 1.2,
        10 to 0.8,
        12 to 1.0,
        2 to 0.6,
        7 to 0.6,
    )

    private val BOOK_HARD_VETO: Set<Int> = setOf(1, 3, 4, 6, 17)
    private val BOOK_REGIME: Set<Int> = BOOK_HARD_VETO
    private val BOOK_QUALITY: Set<Int> = setOf(5, 8, 9, 13, 14, 15)
    private val BOOK_TRIGGER: Set<Int> = setOf(2, 7, 10, 11, 12, 16, 17)

    /** حداقل امتیاز لازم برای «سیگنال» و برای «آماده». */
    internal const val BOOK_MIN_TOTAL_PCT: Double = 80.0
    /** آستانهٔ کل در «حالت حداکثر اجرا» (۳.۹.۶۷). */
    internal const val BOOK_MIN_TOTAL_PCT_MAX_EXEC: Double = 70.0
    /** آستانهٔ هر لایه در «حالت حداکثر اجرا». */
    internal const val BOOK_MIN_LAYER_PCT_MAX_EXEC: Double = 60.0
    internal const val BOOK_MIN_LAYER_PCT: Double = 70.0
    /** آستانهٔ «آماده» (WATCH). قبلاً ۶۶٪ بود و ۴۵٪ کندل‌ها را آماده اعلام می‌کرد. */
    internal const val BOOK_WATCH_TOTAL_PCT: Double = 76.0

    /** کف شمارش شرط برای «آماده» در روش‌های اختصاصی. */
    internal const val BOOK_WATCH_MIN_CONDITIONS: Int = 14

    internal fun bookConfluenceGate(
        conditions: List<StrategyConditionCheck>,
        isBaseModel: Boolean,
        baseConfig: BaseStrategyConfig,
    ): BookGateResult {
        if (!isBaseModel || conditions.isEmpty()) {
            // برای روش‌های اختصاصی (۵۲ کندلی/پارسا/هیدن کراس) این دروازه دخالت نمی‌کند؛
            // منطق خودشان (AND داخلی) دست‌نخورده می‌ماند.
            return BookGateResult(
                hardVetoPassed = true, fires = false, watch = false,
                totalScorePct = 0.0, regimeScorePct = 0.0, qualityScorePct = 0.0, triggerScorePct = 0.0,
                failedHard = emptyList(), bestMissing = emptyList(), noteFa = "",
            )
        }
        val byIndex = conditions.associateBy { it.index }

        fun layerScore(indices: Set<Int>): Pair<Double, Double> {
            var got = 0.0
            var max = 0.0
            indices.forEach { idx ->
                val c = byIndex[idx] ?: return@forEach
                val w = BOOK_WEIGHTS[idx] ?: 1.0
                max += w
                if (c.passed) got += w
            }
            return got to max
        }

        val failedHard = conditions.filter { it.index in BOOK_HARD_VETO && !it.passed }.map { it.titleFa }
        val hardOk = failedHard.isEmpty()

        val (regimeGot, regimeMax) = layerScore(BOOK_REGIME)
        val (qualityGot, qualityMax) = layerScore(BOOK_QUALITY)
        val (triggerGot, triggerMax) = layerScore(BOOK_TRIGGER)
        val got = regimeGot + qualityGot + triggerGot
        val max = (regimeMax + qualityMax + triggerMax).coerceAtLeast(1e-9)

        val totalPct = (got / max) * 100.0
        val regimePct = if (regimeMax > 0.0) (regimeGot / regimeMax) * 100.0 else 100.0
        val qualityPct = if (qualityMax > 0.0) (qualityGot / qualityMax) * 100.0 else 100.0
        val triggerPct = if (triggerMax > 0.0) (triggerGot / triggerMax) * 100.0 else 100.0

        // لایه‌ها: رژیم به‌صورت AND سنجیده شد (۱۰۰٪ لازم است چون همه‌اش وتوی سخت است).
        // کیفیت و ماشه آستانهٔ ۷۰٪ دارند: یعنی هر لایه اجازهٔ از دست دادن یک وزن کوچک را دارد.
        // «حالت حداکثر اجرا»: آستانه‌ها یک پله پایین می‌آیند تا ستاپ واقعیِ شرط‌ها
        // (با وتوی سختِ شرط‌های ۱/۳/۴/۶/۱۷) از دست نرود.
        val minLayerPct = if (baseConfig.allowMaximumExecution) BOOK_MIN_LAYER_PCT_MAX_EXEC else BOOK_MIN_LAYER_PCT
        val minTotalPct = if (baseConfig.allowMaximumExecution) BOOK_MIN_TOTAL_PCT_MAX_EXEC else BOOK_MIN_TOTAL_PCT
        val layersOk = qualityPct >= minLayerPct && triggerPct >= minLayerPct
        val fires = hardOk && layersOk && totalPct >= minTotalPct
        val watch = !fires && hardOk && totalPct >= BOOK_WATCH_TOTAL_PCT

        val missing = conditions.filter { !it.passed }
            .sortedByDescending { BOOK_WEIGHTS[it.index] ?: 0.6 }
            .take(3)
            .map { it.titleFa }

        val noteFa = buildString {
            append(String.format(Locale.US, "هم‌گرایی مکتبی %.0f%%", totalPct))
            append(String.format(Locale.US, " (رژیم %.0f٪ · تعادل %.0f٪ · ماشه %.0f٪)", regimePct, qualityPct, triggerPct))
            if (!hardOk) append(" · وتوی سخت: ").append(failedHard.joinToString("، "))
            else if (!fires) append(" · کمبود: ").append(missing.joinToString("، "))
        }
        return BookGateResult(
            hardVetoPassed = hardOk,
            fires = fires,
            watch = watch,
            totalScorePct = round2(totalPct),
            regimeScorePct = round2(regimePct),
            qualityScorePct = round2(qualityPct),
            triggerScorePct = round2(triggerPct),
            failedHard = failedHard,
            bestMissing = missing,
            noteFa = noteFa,
        )
    }

    /**
     * بررسی دقیق زمان از دست رفتن روند در یک معامله باز که در سود است (`Trend Loss In Profit Detector`):
     * تشخیص می‌دهد کی معامله باز روند خود را از دست داده و دیگر ادامه نمی‌دهد تا همان‌جا که در سود است بسته شود.
     *
     * ۵ نشانه قطعی مرگ روند در سود بر اساس ۴ مکتب ایچیموکو:
     * ۱. شکست خط تعادل کیجون‌سن یا کراس معکوس تنکان/کیجون (`Kijun Breakdown / TK Counter-Cross`)
     * ۲. تخلیه کشیدگی C-Clamp با کندل بازگشتی و شدوی معکوس سنگین زیر/بالای تنکان (`C-Clamp Climax Rejection`)
     * ۳. تکمیل چرخه زمانی هوسودا (9, 17, 26, 33) همراه با فلت شدن تنکان و کندل خلاف جهت (`Hosoda Time Exhaustion`)
     * ۴. برخورد به دیوار فلت تایم بالاتر یا قفل شدن چیکو همراه با سقوط کارایی روند کافمن (`ER14 < 0.20`)
     * ۵. بازگشت قیمت از اوج سود کسب‌شده به زیر سنگر قفل سود تنکان/کیجون (`Profit Giveback Guard`)
     */
    fun evaluateTrendHealthAndProfitExit(
        trade: PaperTrade,
        report: StrategyEvaluationReport?,
        livePriceOverride: Double? = null,
    ): OpenTradeTrendHealthReport {
        val isBuy = trade.action == SignalAction.BUY
        val livePrice = livePriceOverride?.takeIf { it > 0.0 }
            ?: report?.livePrice?.takeIf { it > 0.0 }
            ?: trade.entry
        val initStop = trade.initialStopLoss ?: trade.stopLoss
        val oneR = abs(trade.entry - initStop).coerceAtLeast(1e-8)
        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentR = round2(currentProfitDist / oneR)
        val inProfit = currentR >= 0.35

        val trigSnap = report?.triggerSnapshot
        val stratSnap = report?.strategySnapshot ?: trigSnap
        val rulesSummary = "شروط خروج هوشمند: فقط وقتی معامله به +۲R رسیده باشد و یکی از این‌ها رخ دهد: " +
            "۱) کلوز کندل پشت کیجون یا کراس معکوس TK · ۲) شدوی بازگشتی C-Clamp زیر تنکان · " +
            "۳) اتمام چرخه زمانی هوسودا (9/17/26) با تنکان فلت · ۴) برخورد به دیوار فلت MTF و افت کارایی (ER<20%)"

        if (trigSnap == null || stratSnap == null) {
            return OpenTradeTrendHealthReport(
                tradeId = trade.id,
                state = OpenTradeTrendState.STRONG_CONTINUATION,
                continuationScorePercent = if (currentR >= 0.0) 85 else 65,
                currentProfitR = currentR,
                shouldExitNowInProfit = false,
                exitTriggerReasonFa = "",
                activeWarningSignalsFa = emptyList(),
                profitExitRulesSummaryFa = rulesSummary,
            )
        }

        // Only evaluate candle-snapshot structural breakdown if the snapshot is on the same price scale as livePrice
        val snapMatchesLiveScale = abs(trigSnap.close - livePrice) <= trigSnap.atr14 * 4.0
        val warnings = mutableListOf<String>()
        val fatalProfitTriggers = mutableListOf<String>()

        if (snapMatchesLiveScale) {
            // ۱. شکست خط تعادل کیجون‌سن یا کراس معکوس تنکان/کیجون
            val kijunBroken = if (isBuy) {
                trigSnap.close < trigSnap.kijun - trigSnap.atr14 * 0.06
            } else {
                trigSnap.close > trigSnap.kijun + trigSnap.atr14 * 0.06
            }
            val tkCounterCross = if (isBuy) {
                trigSnap.tenkan < trigSnap.kijun - trigSnap.atr14 * 0.04 && trigSnap.tenkanSlopeAtrPerBar < -0.03
            } else {
                trigSnap.tenkan > trigSnap.kijun + trigSnap.atr14 * 0.04 && trigSnap.tenkanSlopeAtrPerBar > 0.03
            }
            if (kijunBroken || tkCounterCross) {
                warnings += "شکست تعادل کیجون/کراس معکوس TK در تایم ${report.triggerInterval.label}"
                if (inProfit) {
                    fatalProfitTriggers += "شکست کیجون‌سن و چرخش تنکان (${round2(trigSnap.kijun)})"
                }
            }

            // ۲. تخلیه کشیدگی C-Clamp با کندل بازگشتی سنگین
            val adverseWick = if (isBuy) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
            val closedBeyondTenkan = if (isBuy) trigSnap.close < trigSnap.tenkan else trigSnap.close > trigSnap.tenkan
            val cClampReversal = (stratSnap.distanceToKijunAtr >= 1.85 || trigSnap.isCClampWarning) &&
                adverseWick >= 0.48 && closedBeyondTenkan
            if (stratSnap.distanceToKijunAtr >= 1.75 || trigSnap.isCClampWarning) {
                warnings += "هشدار کشیدگی C-Clamp از کیجون (${String.format(Locale.US, "%.1f", stratSnap.distanceToKijunAtr)} ATR)"
            }
            if (cClampReversal && inProfit && currentR >= 0.65) {
                fatalProfitTriggers += "تخلیه کشیدگی C-Clamp با شدوی بازگشتی (${(adverseWick * 100).toInt()}%) و کلوز پشت تنکان"
            }

            // ۳. اتمام چرخه زمانی هوسودا همراه با فلت شدن تنکان و کندل خلاف جهت
            val oppositeCandle = if (isBuy) {
                trigSnap.close < trigSnap.open && trigSnap.closeLocationValue <= 0.35
            } else {
                trigSnap.close > trigSnap.open && trigSnap.closeLocationValue >= 0.65
            }
            val hosodaCycleExhausted = report.waveProjection?.isNearTimeCycleChangeDay == true &&
                trigSnap.isTenkanFlat && oppositeCandle && closedBeyondTenkan
            if (report.waveProjection?.isNearTimeCycleChangeDay == true) {
                warnings += "نزدیک روز چرخش چرخه زمانی هوسودا (عدد ${report.waveProjection.nearestTimeCycleNumber})"
            }
            if (hosodaCycleExhausted && inProfit && currentR >= 0.60) {
                fatalProfitTriggers += "اتمام چرخه زمانی هوسودا (${report.waveProjection?.nearestTimeCycleNumber}) و فلت شدن تنکان"
            }

            // ۴. برخورد چیکو اسپن به مانع و افت شدید کارایی روند کافمن (ER14 < 0.20)
            val chikouBlocked = if (isBuy) !trigSnap.chikouBullishOpenSpace else !trigSnap.chikouBearishOpenSpace
            val momentumCollapsed = chikouBlocked && trigSnap.efficiencyRatio14 < 0.20 && closedBeyondTenkan && oppositeCandle
            if (trigSnap.efficiencyRatio14 < 0.24) {
                warnings += "کاهش کارایی روند کافمن (ER14=${(trigSnap.efficiencyRatio14 * 100).toInt()}%)"
            }
            if (momentumCollapsed && inProfit && currentR >= 0.55) {
                fatalProfitTriggers += "قفل شدن چیکو و سقوط کارایی روند به ${(trigSnap.efficiencyRatio14 * 100).toInt()}%"
            }

            // ۵. تکنیک خروج محرمانه RSI موکرجی (لایهٔ ساختاری ۴ — comic 3): افت بیش از ۱۰ واحد از قله RSI یا شکست قله همراه با کلوز پشت تنکان
            val mukherjeeRsiFailure = if (isBuy) {
                trigSnap.rsiPeakDropLong >= 11.5 && closedBeyondTenkan && oppositeCandle
            } else {
                trigSnap.rsiTroughRiseShort >= 14.0 && closedBeyondTenkan && oppositeCandle
            }
            if (isBuy && trigSnap.rsiPeakDropLong >= 9.0) {
                warnings += String.format(Locale.US, "افت RSI از قله اخیر (%.1f واحد — قانون موکرجی)", trigSnap.rsiPeakDropLong)
            } else if (!isBuy && trigSnap.rsiTroughRiseShort >= 10.0) {
                warnings += String.format(Locale.US, "برگشت RSI از کف اخیر (%.1f واحد — قانون موکرجی)", trigSnap.rsiTroughRiseShort)
            }
            if (mukherjeeRsiFailure && inProfit && currentR >= 0.70) {
                fatalProfitTriggers += String.format(
                    Locale.US,
                    "سیگنال خروج RSI موکرجی (انحراف %.1f واحد از اکسترمم RSI با کلوز پشت تنکان)",
                    if (isBuy) trigSnap.rsiPeakDropLong else trigSnap.rsiTroughRiseShort,
                )
            }

            // ۶. سیستم ۴ قانون خروج کونیتز (لایه‌های ۸ و ۹): شکست هم‌زمان کیجون و EMA34 همراه با برگشت MACD/DMI
            val koonitz4RuleExit = if (isBuy) {
                trigSnap.close < trigSnap.ema34 && trigSnap.close < trigSnap.kijun &&
                    (trigSnap.isMacdBearish || trigSnap.isDmiBearish)
            } else {
                trigSnap.close > trigSnap.ema34 && trigSnap.close > trigSnap.kijun &&
                    (trigSnap.isMacdBullish || trigSnap.isDmiBullish)
            }
            if (koonitz4RuleExit && inProfit && currentR >= 0.50) {
                fatalProfitTriggers += "تکمیل ۴ قانون خروج کونیتز (شکست هم‌زمان کیجون و EMA34 با چرخش MACD/DMI)"
            }
        }

        // قانون کاربر: خروج هوشمند فقط وقتی مجاز است که معامله دست‌کم +۲R در سود باشد.
        val shouldExitInProfit = inProfit && currentR >= MIN_SMART_EXIT_PROFIT_R && fatalProfitTriggers.isNotEmpty()
        val rawScore = 92 - (warnings.size * 18) - (fatalProfitTriggers.size * 35) + (if (currentR >= 1.5) 8 else 0)
        val score = rawScore.coerceIn(10, 99)

        val state = when {
            shouldExitInProfit -> OpenTradeTrendState.TREND_LOST_EXIT_IN_PROFIT
            warnings.isNotEmpty() -> OpenTradeTrendState.MOMENTUM_COOLING_LOCK_ACTIVE
            else -> OpenTradeTrendState.STRONG_CONTINUATION
        }

        val exitReason = if (shouldExitInProfit) {
            "⚡ خروج هوشمند در سود (+${String.format(Locale.US, "%.2f", currentR)}R) به دلیل اتمام روند: ${fatalProfitTriggers.joinToString(" + ")}"
        } else {
            ""
        }

        return OpenTradeTrendHealthReport(
            tradeId = trade.id,
            state = state,
            continuationScorePercent = score,
            currentProfitR = currentR,
            shouldExitNowInProfit = shouldExitInProfit,
            exitTriggerReasonFa = exitReason,
            activeWarningSignalsFa = warnings,
            profitExitRulesSummaryFa = rulesSummary,
        )
    }

    /**
     * موتور مدیریت معاملات باز با معماری «حد ضرر ساختاری کاملاً ثابت (غیرپله‌ای) + حفظ R:R اولیه + خروج هوشمند در اتمام روند»:
     * - حد ضرر (`stopLoss`) تحت هیچ شرایطی به‌صورت پله‌ای (`Break-Even` یا تریل پلکانی) جابجا نمی‌شود و تا پایان معامله
     *   دقیقاً روی همان حد ضرر ساختاری اولیه (`initialStopLoss ?: stopLoss`) ثابت می‌ماند.
     * - نسبت `R:R` و حد سود (`takeProfit`) دقیقاً همان مقدار اولیه زمان باز شدن معامله باقی می‌ماند و حتی اگر `R:R > 3.0` باشد،
     *   حد ضرر ثابت مانده و معامله تا هدف اولیه خود (`takeProfit`) یا تشخیص قطعی برگشت روند ادامه می‌یابد.
     */
    fun evaluateOpenTradeManagement(
        trade: PaperTrade,
        report: StrategyEvaluationReport,
        config: BaseStrategyConfig,
    ): PreserveModeEvaluation? {
        if (!trade.isOpen || V1Universe.normalize(trade.symbol) != V1Universe.normalize(report.symbol)) return null
        val trigSnap = report.triggerSnapshot ?: return null
        val livePrice = report.livePrice.takeIf { it > 0.0 } ?: return null

        val fixedStopLoss = round2(trade.initialStopLoss ?: trade.stopLoss)
        val oneRDistance = abs(trade.entry - fixedStopLoss).coerceAtLeast(trigSnap.atr14 * 0.4)
        val isBuy = trade.action == SignalAction.BUY

        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentRMultiple = currentProfitDist / oneRDistance
```

</details>

### `bookConfluenceGate()` — بخش اختصاصی پیمان

```kotlin
    internal fun bookConfluenceGate(
        conditions: List<StrategyConditionCheck>,
        isBaseModel: Boolean,
        baseConfig: BaseStrategyConfig,
    ): BookGateResult {
        if (!isBaseModel || conditions.isEmpty()) {
            // برای روش‌های اختصاصی (۵۲ کندلی/پارسا/هیدن کراس) این دروازه دخالت نمی‌کند؛
            // منطق خودشان (AND داخلی) دست‌نخورده می‌ماند.
            return BookGateResult(
                hardVetoPassed = true, fires = false, watch = false,
                totalScorePct = 0.0, regimeScorePct = 0.0, qualityScorePct = 0.0, triggerScorePct = 0.0,
                failedHard = emptyList(), bestMissing = emptyList(), noteFa = "",
            )
        }
        val byIndex = conditions.associateBy { it.index }

        fun layerScore(indices: Set<Int>): Pair<Double, Double> {
            var got = 0.0
            var max = 0.0
            indices.forEach { idx ->
                val c = byIndex[idx] ?: return@forEach
                val w = BOOK_WEIGHTS[idx] ?: 1.0
                max += w
                if (c.passed) got += w
            }
            return got to max
        }

        val failedHard = conditions.filter { it.index in BOOK_HARD_VETO && !it.passed }.map { it.titleFa }
        val hardOk = failedHard.isEmpty()

        val (regimeGot, regimeMax) = layerScore(BOOK_REGIME)
        val (qualityGot, qualityMax) = layerScore(BOOK_QUALITY)
        val (triggerGot, triggerMax) = layerScore(BOOK_TRIGGER)
        val got = regimeGot + qualityGot + triggerGot
        val max = (regimeMax + qualityMax + triggerMax).coerceAtLeast(1e-9)

        val totalPct = (got / max) * 100.0
        val regimePct = if (regimeMax > 0.0) (regimeGot / regimeMax) * 100.0 else 100.0
        val qualityPct = if (qualityMax > 0.0) (qualityGot / qualityMax) * 100.0 else 100.0
        val triggerPct = if (triggerMax > 0.0) (triggerGot / triggerMax) * 100.0 else 100.0

        // لایه‌ها: رژیم به‌صورت AND سنجیده شد (۱۰۰٪ لازم است چون همه‌اش وتوی سخت است).
        // کیفیت و ماشه آستانهٔ ۷۰٪ دارند: یعنی هر لایه اجازهٔ از دست دادن یک وزن کوچک را دارد.
        // «حالت حداکثر اجرا»: آستانه‌ها یک پله پایین می‌آیند تا ستاپ واقعیِ شرط‌ها
        // (با وتوی سختِ شرط‌های ۱/۳/۴/۶/۱۷) از دست نرود.
        val minLayerPct = if (baseConfig.allowMaximumExecution) BOOK_MIN_LAYER_PCT_MAX_EXEC else BOOK_MIN_LAYER_PCT
        val minTotalPct = if (baseConfig.allowMaximumExecution) BOOK_MIN_TOTAL_PCT_MAX_EXEC else BOOK_MIN_TOTAL_PCT
        val layersOk = qualityPct >= minLayerPct && triggerPct >= minLayerPct
        val fires = hardOk && layersOk && totalPct >= minTotalPct
        val watch = !fires && hardOk && totalPct >= BOOK_WATCH_TOTAL_PCT

        val missing = conditions.filter { !it.passed }
            .sortedByDescending { BOOK_WEIGHTS[it.index] ?: 0.6 }
            .take(3)
            .map { it.titleFa }

        val noteFa = buildString {
            append(String.format(Locale.US, "هم‌گرایی مکتبی %.0f%%", totalPct))
            append(String.format(Locale.US, " (رژیم %.0f٪ · تعادل %.0f٪ · ماشه %.0f٪)", regimePct, qualityPct, triggerPct))
            if (!hardOk) append(" · وتوی سخت: ").append(failedHard.joinToString("، "))
            else if (!fires) append(" · کمبود: ").append(missing.joinToString("، "))
        }
        return BookGateResult(
            hardVetoPassed = hardOk,
            fires = fires,
            watch = watch,
            totalScorePct = round2(totalPct),
            regimeScorePct = round2(regimePct),
            qualityScorePct = round2(qualityPct),
            triggerScorePct = round2(triggerPct),
            failedHard = failedHard,
            bestMissing = missing,
            noteFa = noteFa,
        )
    }
```
### کد کامل این استراتژی (پوشهٔ جداگانه)

| فایل | خط | داخل این سند |
|---|---|---|
| `code/16-peyman-drive-books/NeoWaveEngine.kt` | ۱۲۸۱ | فقط در فایل کپی |
| `code/16-peyman-drive-books/PeymanCryptoEngine.kt` | ۶۰۸ | فقط در فایل کپی |
| `code/16-peyman-drive-books/PeymanCryptoModels.kt` | ۴۱۹ | فقط در فایل کپی |
| `code/16-peyman-drive-books/PeymanMarketContextEngine.kt` | ۳۹۴ | فقط در فایل کپی |
| `code/16-peyman-drive-books/CryptoStructuralBreakEngine.kt` | ۴۴۸ | فقط در فایل کپی |
| `code/16-peyman-drive-books/NeoWaveCountEnsemble.kt` | ۱۴۷ | کامل، پایین |

**core/peyman/NeoWaveCountEnsemble.kt**
<details><summary>کد کامل (۱۴۷ خط)</summary>

```kotlin
package com.aurum.edge.core.peyman

import com.aurum.edge.core.NeoWaveEngine
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.max

/**
 * # شمارش اصلی و جایگزین نئوویو
 *
 * قانون‌های این لایه:
 * - نئوویو **جهت، ساختار و نقطهٔ ابطال** می‌دهد، نه مجوز ورود.
 * - شمارش با Hard Breach حذف می‌شود (قانون نیلی)؛ نه اینکه با درصد احتمال پوشانده شود.
 * - اگر شمارش اصلی و جایگزین مخالف باشند، امتیاز ساختار پایین می‌آید و ورود Watch می‌شود.
 * - هیچ «احتمال برد» کالیبره‌نشده‌ای تولید نمی‌شود.
 */
data class NeoWaveCountCandidate(
    val id: String,
    val direction: SignalAction,
    val invalidationPrice: Double?,
    val targets: List<Double>,
    val structureScore: Double,
    val rulesPassed: Int,
    val rulesTotal: Int,
    val confidencePct: Double,
    val isPrimary: Boolean,
    val noteFa: String,
)

data class NeoWaveCountEnsembleResult(
    val primary: NeoWaveCountCandidate?,
    val alternatives: List<NeoWaveCountCandidate>,
    val directionalAgreement: Boolean,
    val agreement: NeoWaveEnsembleAgreement,
    val scoreOutOf25: Double,
    val invalidationMapFa: String,
)

object NeoWaveCountEnsemble {

    /** دامنهٔ زیگزاگ بر اساس رژیم نوسان (همه از دادهٔ گذشته). */
    fun amplitudeFor(regime: CryptoMarketRegime, atrPercentile: Double, config: PeymanCryptoConfig): Double =
        when {
            regime == CryptoMarketRegime.ILLIQUID_SHOCK -> config.zigzagAmpHighVol
            atrPercentile <= 30.0 -> config.zigzagAmpLowVol
            atrPercentile >= 70.0 -> config.zigzagAmpHighVol
            else -> config.zigzagAmpNormal
        }

    fun build(
        strategyBars: List<Candle>,
        triggerBars: List<Candle>,
        regime: CryptoMarketRegime,
        atrPercentile: Double,
        config: PeymanCryptoConfig,
    ): NeoWaveCountEnsembleResult {
        val closed = PeymanMath.closed(strategyBars)
        val baseAmp = amplitudeFor(regime, atrPercentile, config)
        val variants = linkedMapOf(
            "primary" to (closed to baseAmp),
            "alternative-narrow" to (closed to max(0.20, baseAmp * 0.75)),
            "alternative-wide" to (closed to baseAmp * 1.30),
            "trigger-tf" to (PeymanMath.closed(triggerBars) to max(0.20, baseAmp * 0.60)),
        )

        val candidates = ArrayList<NeoWaveCountCandidate>()
        for ((id, pair) in variants) {
            val (bars, amp) = pair
            val verdict = try {
                NeoWaveEngine.analyze(bars, amp)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (verdict.hardBreach) continue
            if (verdict.pattern == NeoWaveEngine.NeoWavePattern.UNKNOWN) continue
            val score = structureScore(verdict.rulesPassed, verdict.rulesTotal, verdict.confidencePct, verdict.isConfirmed)
            candidates.add(
                NeoWaveCountCandidate(
                    id = id,
                    direction = verdict.expectedDirection,
                    invalidationPrice = verdict.invalidationPrice,
                    targets = listOfNotNull(verdict.nextTarget),
                    structureScore = score,
                    rulesPassed = verdict.rulesPassed,
                    rulesTotal = verdict.rulesTotal,
                    confidencePct = verdict.confidencePct,
                    isPrimary = id == "primary",
                    noteFa = verdict.summaryFa,
                ),
            )
        }

        if (candidates.isEmpty()) {
            return NeoWaveCountEnsembleResult(
                null, emptyList(), false, NeoWaveEnsembleAgreement.NONE, 0.0,
                "هیچ شمارش معتبری وجود ندارد.",
            )
        }

        val sorted = candidates.sortedByDescending { it.structureScore }
        val primary = sorted.firstOrNull { it.isPrimary } ?: sorted.first()
        val alternatives = sorted.filter { it !== primary }.take(3)

        val directions = (listOfNotNull(primary) + alternatives).map { it.direction }.distinct()
        val agreement = when {
            alternatives.isEmpty() -> NeoWaveEnsembleAgreement.SINGLE
            directions.size == 1 -> NeoWaveEnsembleAgreement.AGREE
            else -> NeoWaveEnsembleAgreement.CONFLICT
        }

        // امتیاز ۰..۲۵: کیفیت شمارش اصلی + پاداش هم‌جهتی جایگزین‌ها، بدون درصد احتمال.
        //
        // کالیبراسیون: سقف هر مؤلفه باید واقعاً دست‌یافتنی باشد، وگرنه آستانه‌های ۷۵/۸۰/۸۵
        // فقط روی کاغذ وجود دارند. اندازه‌گیری روی سری ساختگی نشان داد ضریب ۰٫۷ سقف عملی
        // این مؤلفه را به ~۱۹ می‌رساند و ستاپ سالم را زیر آستانه می‌برد؛ پس وزن کامل داده شد.
        var score = primary.structureScore
        if (agreement == NeoWaveEnsembleAgreement.AGREE) score += 3.0
        if (agreement == NeoWaveEnsembleAgreement.CONFLICT) score -= 8.0
        score = score.coerceIn(0.0, 25.0)

        val invalidationMap = buildString {
            append("ابطال اصلی: ")
            append(primary.invalidationPrice?.let { PeymanMath.faDigits("%.2f".format(java.util.Locale.US, it)) } ?: "نامشخص")
            alternatives.forEach { alt ->
                append(" · جایگزین ${alt.id}: ")
                append(alt.invalidationPrice?.let { PeymanMath.faDigits("%.2f".format(java.util.Locale.US, it)) } ?: "نامشخص")
            }
        }

        return NeoWaveCountEnsembleResult(
            primary = primary,
            alternatives = alternatives,
            directionalAgreement = directions.size == 1,
            agreement = agreement,
            scoreOutOf25 = score,
            invalidationMapFa = invalidationMap,
        )
    }

    private fun structureScore(passed: Int, total: Int, confidence: Double, confirmed: Boolean): Double {
        val t = total.coerceAtLeast(1)
        val rulePart = (passed.toDouble() / t).coerceIn(0.0, 1.0) * 18.0
        val confPart = (confidence.coerceIn(0.0, 100.0) / 100.0) * 4.0
        val confirmPart = if (confirmed) 3.0 else 0.0
        return (rulePart + confPart + confirmPart).coerceIn(0.0, 25.0)
    }
}
```

</details>

| `code/16-peyman-drive-books/CryptoExecutionQualityGate.kt` | ۲۲۷ | کامل، پایین |

**core/peyman/CryptoExecutionQualityGate.kt**
<details><summary>کد کامل (۲۲۷ خط)</summary>

```kotlin
package com.aurum.edge.core.peyman

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # کیفیت اجرا و هزینه
 *
 * R:R بدون کسر هزینه، یک عدد خوش‌بینانه است. این‌جا کارمزد رفت‌وبرگشت، اسپرد و
 * Slippage محافظه‌کارانه از پاداش کم و به ریسک اضافه می‌شوند تا تصمیم با عدد واقعی گرفته شود.
 *
 * نکتهٔ واحدها: همهٔ ورودی‌ها بر حسب **قیمت** هستند؛ درصد کارمزد یک‌بار به قیمت تبدیل می‌شود
 * و بافر شکست هم از قبل بر حسب قیمت است — تبدیل دوباره انجام نمی‌شود.
 */
data class ExecutionQuality(
    val ok: Boolean,
    val costPrice: Double,
    val netRisk: Double,
    val netReward: Double,
    val effectiveRiskReward: Double,
    val obstaclePrice: Double?,
    val obstacleSpaceR: Double?,
    val distanceFromKijunAtr: Double?,
    val liquidityAvailable: Boolean,
    val reasonsFa: List<String>,
) {
    val reasonsJoinedFa: String get() = reasonsFa.joinToString(" ")
}

object CryptoExecutionQualityGate {

    fun evaluate(
        symbol: String,
        direction: SignalAction,
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        atr: Double,
        triggerBars: List<Candle>,
        config: PeymanCryptoConfig,
        minRiskReward: Double = config.minRiskReward,
        requireVolume: Boolean = true,
        /**
         * مانع‌ها فقط از کندل‌های **قبل از** این ایندکس خوانده می‌شوند.
         *
         * چرا؟ سطحی که همین حالا شکسته شده، مقاومت نیست؛ اگر کندل شکست هم در جست‌وجوی
         * مانع بیاید، «سقف خودِ شکست» به‌عنوان مانع نزدیک دیده می‌شود و هر ورود
         * بازآزمایی با «فضای ۰٫۴R» رد می‌شود (اندازه‌گیری‌شده، نه فرضی).
         */
        obstacleBeforeIndex: Int? = null,
    ): ExecutionQuality {
        val isLong = direction == SignalAction.BUY
        val reasons = ArrayList<String>()
        if (!PeymanMath.isFinitePositive(entry) || !PeymanMath.isFinitePositive(atr)) {
            return ExecutionQuality(
                false, 0.0, 0.0, 0.0, 0.0, null, null, null, false,
                listOf("قیمت ورود یا ATR نامعتبر است."),
            )
        }
        val risk = abs(entry - stopLoss)
        val reward = abs(takeProfit - entry)
        if (risk <= 0.0 || reward <= 0.0) {
            return ExecutionQuality(
                false, 0.0, 0.0, 0.0, 0.0, null, null, null, false,
                listOf("حد ضرر یا حد سود نامعتبر است."),
            )
        }

        // هزینهٔ رفت‌وبرگشت + اسپرد + Slippage (همه بر حسب قیمت).
        val feePrice = entry * (config.roundTurnFeePct + config.spreadPct) / 100.0
        val slippagePrice = config.slippageAtrFraction * atr
        val costPrice = feePrice + slippagePrice
        val netRisk = risk + costPrice
        val netReward = reward - costPrice
        val rr = if (netRisk > 0.0) netReward / netRisk else 0.0

        // مانع ساختاری: نزدیک‌ترین سقف/کف تاریخی **قبل از** کندل جاری (بدون نگاه به آینده).
        val closed = PeymanMath.closed(triggerBars)
        val obstacle = if (closed.size >= 10) {
            nearestObstacle(closed, entry, isLong, obstacleBeforeIndex ?: (closed.size - 1), atr)
        } else {
            null
        }
        val spaceR = obstacle?.let { abs(it - entry) / risk } 

        // فاصله از کیجون به‌عنوان سقف تعقیب.
        val kijun = if (closed.size >= 26) {
            val window = closed.subList(closed.size - 26, closed.size)
            (window.maxOf { it.high } + window.minOf { it.low }) / 2.0
        } else {
            null
        }
        val kijunDist = kijun?.let { abs(entry - it) / atr }

        if (rr < minRiskReward) {
            reasons.add(
                "R:R مؤثر پس از هزینه ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, rr))} " +
                    "کمتر از حداقل ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, minRiskReward))} است.",
            )
        }
        if (spaceR != null && spaceR < config.minObstacleSpaceR) {
            reasons.add("فضای تا مانع فقط ${PeymanMath.faDigits("%.1f".format(java.util.Locale.US, spaceR))}R است.")
        }
        if (kijunDist != null && kijunDist > config.maxKijunDistanceAtr) {
            reasons.add(
                "فاصله از کیجون ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, kijunDist))} ATR است؛ تعقیب ممنوع.",
            )
        }
        val liquidity = PeymanMath.volumeAvailable(closed)
        if (requireVolume && !liquidity) {
            reasons.add("دادهٔ حجم/نقدشوندگی معتبر نیست؛ کیفیت اجرا قابل تأیید نیست.")
        }
        // حجم غیرعادی + شدوی مخالف بزرگ = کندل شوک؛ اجرا در آن‌جا بی‌کیفیت است.
        val last = closed.lastOrNull()
        if (last != null) {
            val rvol = PeymanMath.relativeVolume(closed, closed.size - 1)
            val adverseWick = if (isLong) PeymanMath.upperWickRatio(last) else PeymanMath.lowerWickRatio(last)
            if (rvol != null && rvol >= 3.0 && adverseWick >= 0.35) {
                reasons.add("کندل شوک با شدوی مخالف بزرگ؛ کیفیت اجرا پایین است.")
            }
        }

        return ExecutionQuality(
            ok = reasons.isEmpty(),
            costPrice = costPrice,
            netRisk = netRisk,
            netReward = netReward,
            effectiveRiskReward = rr,
            obstaclePrice = obstacle,
            obstacleSpaceR = spaceR,
            distanceFromKijunAtr = kijunDist,
            liquidityAvailable = liquidity,
            reasonsFa = reasons,
        )
    }

    private fun nearestObstacle(
        bars: List<Candle>,
        entry: Double,
        isLong: Boolean,
        beforeIndex: Int,
        atr: Double,
    ): Double? {
        // فقط ۵۰ کندل قبل از کندل جاری و فقط قبل از کندل شکست.
        val to = minOf(beforeIndex - 1, bars.size - 2)
        val from = max(0, to - 50)
        if (to <= from) return null
        // مانع باید حداقل نیم ATR دورتر از ورود باشد، وگرنه نویز است.
        val minGap = 0.5 * atr
        var best: Double? = null
        for (k in from..to) {
            val level = if (isLong) bars[k].high else bars[k].low
            val ahead = if (isLong) level > entry + minGap else level < entry - minGap
            if (!ahead) continue
            best = when {
                best == null -> level
                isLong -> minOf(best, level)
                else -> maxOf(best, level)
            }
        }
        return best
    }
}

/**
 * # ریسک پرتفوی و خوشه‌های هم‌بسته
 *
 * «سه پوزیشن Long روی سه آلت هم‌جهت» سه ریسک مستقل نیست؛ یک ریسک است. این گیت اجازه
 * نمی‌دهد محدودیت با تغییر نام نماد دور زده شود و هیچ مسیر Martingale هم ندارد.
 */
object CryptoPortfolioRiskGate {

    fun evaluate(
        direction: SignalAction,
        openPositions: List<com.aurum.edge.core.PaperTrade>,
        config: PeymanCryptoConfig,
        todayLossPct: Double,
        consecutiveLosses: Int,
        fakeBreakoutsSinceLastWin: Int,
        isReversal: Boolean = false,
    ): PeymanGateResult {
        if (todayLossPct >= config.dailyLossLimitPct) {
            return PeymanGateResult.fail(
                "DAILY_LOSS_LIMIT",
                "سقف ضرر روزانه پر شده است (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, todayLossPct))}٪).",
            )
        }
        if (consecutiveLosses >= config.consecutiveLossCooldown) {
            return PeymanGateResult.fail(
                "LOSS_STREAK_COOLDOWN",
                "پس از ${PeymanMath.faDigits(consecutiveLosses.toString())} ضرر متوالی ورود جدید مجاز نیست.",
            )
        }
        if (fakeBreakoutsSinceLastWin >= 1) {
            return PeymanGateResult.fail(
                "FAKE_BREAKOUT_COOLDOWN",
                "آخرین شکست ناموفق بود؛ تا پایان کول‌داون ورود جدید مجاز نیست.",
            )
        }
        val riskPerTrade = effectiveRiskPct(config, isReversal)
        val cryptoOpen = openPositions.filter { com.aurum.edge.core.AssetClass.of(it.symbol) == com.aurum.edge.core.AssetClass.CRYPTO }
        val cluster = cryptoOpen.filter { it.action == direction }
        val clusterRisk = cluster.size * config.riskPercentPerTrade
        if (clusterRisk + riskPerTrade > config.maxClusterRiskPct) {
            return PeymanGateResult.fail(
                "CLUSTER_RISK",
                "ریسک خوشهٔ هم‌جهت (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, clusterRisk))}٪) اجازهٔ پوزیشن تازه نمی‌دهد.",
            )
        }
        val totalRisk = openPositions.size * config.riskPercentPerTrade
        if (totalRisk + riskPerTrade > config.maxTotalRiskPct) {
            return PeymanGateResult.fail(
                "TOTAL_RISK",
                "مجموع ریسک باز (${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, totalRisk))}٪) به سقف رسیده است.",
            )
        }
        return PeymanGateResult.ok("PORTFOLIO_OK", "")
    }

    /** ریسک واقعی هر معامله؛ Reversal حداکثر نصف. */
    fun effectiveRiskPct(config: PeymanCryptoConfig, isReversal: Boolean): Double =
        if (isReversal) config.riskPercentPerTrade * config.reversalRiskFraction.coerceIn(0.1, 1.0)
        else config.riskPercentPerTrade
}
```

</details>


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
tools/strategy_lab/run.sh 8000 11 pey

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/core/PeymanStrategyTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/CustomIchimokuStrategiesTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/RiskAndCalendarEngineTest.kt`

> این استراتژی کد اختصاصی دارد؛ برای مرور کامل، فایل‌های `code/` را جدا بردار.
