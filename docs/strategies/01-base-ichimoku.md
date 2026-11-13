# ۱ · استراتژی بیس (ترکیب کامل ۴گانه: پاتل+تاتل+هوسودا+پلویل)
`BASE_ICHIMOKU` · ترکیب ۴گانه · بیس جامع

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `BASE_ICHIMOKU` |
| نشان | ترکیب ۴گانه · بیس جامع |
| مکتب‌ها | پاتل + تاتل + هوسودا + پلویل |
| تعداد مکتب | ۴ از ۴ |
| R:R پایه | ۲.۲۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | پاتل + تاتل + هوسودا + پلویل |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
ترکیب کامل هر ۴ ستون ایچیموکو: تعادل کیجون پاتل، فیلتر چیکو و بریک‌اوت تاتل، امواج N/V/E و چرخه زمان هوسودا، و پلاتوهای فلت سه‌تایم‌فریمی کارن پلویل.

## دروازه‌هایی که این استراتژی روشن می‌کند
این پنج قاعده، دقیقاً همان چیزی است که کد از پرچم مکتب‌ها می‌سازد (`IchimokuStrategyEngine.kt`، بلوک `effectiveConfig`):

| دروازه | قاعدهٔ کد | وضعیت |
|---|---|---|
| ماشهٔ پولبک | `hasPatel یا hasHosoda یا hasPeloille یا بی‌تاتل` | روشن |
| ماشهٔ شکست | `hasTuttle یا hasHosoda یا hasPeloille یا بی‌پاتل` | روشن |
| الزام همسویی تایم بالا | `hasPeloille یا ترکیب ۲گانه و بیشتر` | روشن |
| الزام فضای آزاد چیکو | `hasTuttle یا hasPeloille یا ترکیب ۲گانه و بیشتر` | روشن |
| الزام شیب ابر آینده | `hasPatel یا hasTuttle یا ترکیب ۲گانه و بیشتر` | روشن |

## شرط‌های ارزیابی

| # | شرط | مربوط به |
|---|---|---|
| ۱ | رژیم کوموی ۳ تایم‌فریم (تایم بالاتر/استراتژی/ماشه) | مشترک |
| ۲ | چیدمان ۵ خط سانیاکو (Sanyaku Order) | مشترک |
| ۳ | کراس و شیب پویا Tenkan / Kijun | مکتب پاتل |
| ۴ | جهت و شیب ابر آینده (۲۶ کندل جلوتر) | مشترک |
| ۵ | ضخامت ابر آینده و ایمنی از پیچش (Twist) | مکتب هوسودا + پلویل |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۷ | شکست سقف/کف ساختاری چیکو (فیلتر تاتل) | مکتب تاتل |
| ۸ | قانون کش لاستیکی کیجون و نبود C-Clamp | مکتب پاتل |
| ۹ | فیلتر جاذبه فلت SSB، دیوار تاج هیدن کراس و تعادل کاتانا | مکتب تاتل + پلویل |
| ۱۰ | هندسه موج N هوسودا، نقطه S و چرخه زمانی | مکتب هوسودا |
| ۱۱ | قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol) | مشترک |
| ۱۲ | فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت | مشترک |
| ۱۳ | کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14) | مشترک |
| ۱۴ | فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس | مشترک |
| ۱۵ | شکار اصل روند (فاز Genesis/Core و عدم اشباع) | مشترک |
| ۱۶ | نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket) | مشترک |
| ۱۷ | اقتصاد اجرا — هزینهٔ رفت‌وبرگشت در برابر ۱R | مشترک |

شرط‌های پایه (۱، ۲، ۴، ۶) و لایهٔ کیفیت اجرا (۱۱ تا ۱۷) برای همهٔ ۱۵ روش ایچیموکو یکی است؛ فقط شرط‌های مکتبی (۳، ۵، ۷، ۸، ۹، ۱۰) با پرچم مکتب‌ها روشن/خاموش می‌شوند. توضیح کامل در سند «۰۰ — موتور مشترک».

## کد
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
**Check 1: 3-TF Kumo Regime Alignment (4H / 1H / 15m)** — خطوط ۳۵۸۲ تا ۳۵۹۹

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
**Check 2: Sanyaku 5-Line Order (Price > Tenkan >= Kijun > Kumo)** — خطوط ۳۶۰۰ تا ۳۶۱۱

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
**Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope** — خطوط ۳۶۱۲ تا ۳۶۳۰

```kotlin
        // ── Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope ──
        val tkAligned = if (isLong) {
            stratSnap.tenkan >= stratSnap.kijun &&
                stratSnap.tenkan >= stratSnap.prevTenkan - stratSnap.atr14 * 0.05 &&
                stratSnap.kijun >= stratSnap.prevKijun - stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan >= trigSnap.prevTenkan - trigSnap.atr14 * 0.05
        } else {
            stratSnap.tenkan <= stratSnap.kijun &&
                stratSnap.tenkan <= stratSnap.prevTenkan + stratSnap.atr14 * 0.05 &&
                stratSnap.kijun <= stratSnap.prevKijun + stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan <= trigSnap.prevTenkan + trigSnap.atr14 * 0.05
        }
        val cond3Detail = String.format(
            Locale.US,
            "شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR",
            trigSnap.tenkanSlopeAtrPerBar,
            trigSnap.kijunSlopeAtrPerBar,
        )
```
**Check 4: Future Kumo Direction & Non-diverging Span B (26 bars ahead)** — خطوط ۳۶۳۱ تا ۳۶۴۲

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
**Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل)** — خطوط ۳۶۴۳ تا ۳۶۵۳

```kotlin
        // ── Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل) ────
        val minThickStrat = effectiveConfig.minFutureKumoThicknessAtr
        val minThickTrig = (minThickStrat * 0.80).coerceAtLeast(0.02)
        val futureThicknessOk = stratSnap.futureKumoThicknessAtr >= minThickStrat && trigSnap.futureKumoThicknessAtr >= minThickTrig
        val cond5Detail = String.format(
            Locale.US,
            "ضخامت ابر آینده: %.2f ATR (${config.strategyInterval.label}) · %.2f ATR (${config.triggerInterval.label})",
            stratSnap.futureKumoThicknessAtr,
            trigSnap.futureKumoThicknessAtr,
        )
```
**Check 6: Chikou Span 3D Open Space (26 bars back + 6-bar corridor)** — خطوط ۳۶۵۴ تا ۳۶۶۷

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
**Check 7: Chikou Structural Peak / Trough Clearance (مارک تاتل)** — خطوط ۳۶۶۸ تا ۳۶۷۹

```kotlin
        // ── Check 7: Chikou Structural Peak / Trough Clearance (مارک تاتل) ─────
        val chikouPeakClear = if (isLong) {
            trigSnap.close >= trigSnap.chikouResistancePeak - trigSnap.atr14 * 0.15
        } else {
            trigSnap.close <= trigSnap.chikouSupportTrough + trigSnap.atr14 * 0.15
        }
        val cond7Detail = if (isLong) {
            "کلوز=${trigSnap.close} در برابر سقف چیکو=${trigSnap.chikouResistancePeak}"
        } else {
            "کلوز=${trigSnap.close} در برابر کف چیکو=${trigSnap.chikouSupportTrough}"
        }
```
**Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter** — خطوط ۳۶۸۰ تا ۳۶۹۱

```kotlin
        // ── Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter ─────
        val extAtr = stratSnap.distanceToKijunAtr
        val notOverextended = extAtr <= effectiveConfig.maxKijunExtensionAtr &&
            !stratSnap.isCClampWarning && !trigSnap.isCClampWarning
        val cond8Detail = String.format(
            Locale.US,
            "فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s",
            extAtr,
            effectiveConfig.maxKijunExtensionAtr,
            if (stratSnap.isCClampWarning || trigSnap.isCClampWarning) " · هشدار C-Clamp" else "",
        )
```
**Check 9: Flat SSB Gravity & Katana Equilibrium Filter (تاتل + پلویل)** — خطوط ۳۶۹۲ تا ۳۸۰۲
<details><summary>کد کامل (۱۱۱ خط)</summary>

```kotlin
        // ── Check 9: Flat SSB Gravity & Katana Equilibrium Filter (تاتل + پلویل) ──
        val reqGravityClearance = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * effectiveConfig.breakoutBufferAtr
        } else {
            0.0
        }
        val flatSsbGravityCleared = if (isLong) {
            trigSnap.close >= trigSnap.kumoTop + reqGravityClearance
        } else {
            trigSnap.close <= trigSnap.kumoBottom - reqGravityClearance
        }
        // لایهٔ هیدن کراس در شرط ۹: دیوار «تاج» هم مثل SSB فلت جاذبه دارد.
        // این بخش با `includeHiddenCrossLayer` خاموش‌شدنی است تا «بیس خالص چهارگانه» هم قابل ارزیابی باشد.
        val hiddenCrossWallClearance = if (effectiveConfig.includeHiddenCrossLayer && trigSnap.nearestCrownLevel > 0.0) {
            val wallDist = abs(trigSnap.nearestCrownLevel - trigSnap.close)
            val wallAhead = if (isLong) {
                trigSnap.nearestCrownLevel > trigSnap.close
            } else {
                trigSnap.nearestCrownLevel < trigSnap.close
            }
            !wallAhead || wallDist >= trigSnap.atr14 * effectiveConfig.breakoutBufferAtr
        } else {
            true
        }
        val cond9Detail = buildString {
            if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
                append("SSB فلت شناسایی شد؛ عبور با بافر امن انجام شد")
            } else {
                append("بدون تله جاذبه SSB فلت (${if (trigSnap.isKatanaEquilibrium) "هم‌پوشانی کاتانا" else "تفکیک استاندارد TK"})")
            }
            if (effectiveConfig.includeHiddenCrossLayer && trigSnap.nearestCrownLevel > 0.0) {
                append(String.format(
                    Locale.US,
                    " · دیوار تاج هیدن کراس=%.2f (%.2f ATR) %s",
                    trigSnap.nearestCrownLevel,
                    trigSnap.nearestCrownDistanceAtr,
                    if (hiddenCrossWallClearance) "پاک" else "درگیر",
                ))
            }
        }

        // سه کندل پایانی برای این دو روش الگوی تصمیم نیستند؛ منطقشان صرفاً از پنجرهٔ بلند و بسته می‌آید.
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

        val parsaSpanATurnOk = if (isDirectionalLong) {
            trigSnap.previousSenkouSpanASlopeAtrPerBar <= 0.0 && trigSnap.senkouSpanASlopeAtrPerBar >= 0.025
        } else {
            trigSnap.previousSenkouSpanASlopeAtrPerBar >= 0.0 && trigSnap.senkouSpanASlopeAtrPerBar <= -0.025
        }
        val parsaCrossAge = if (isDirectionalLong) trigSnap.bullishTenkanKijunCrossAgeBars else trigSnap.bearishTenkanKijunCrossAgeBars
        val parsaFlatOk = trigSnap.senkouSpanBFlatBars >= 18
        val parsaSlopeOk = if (isDirectionalLong) {
            trigSnap.tenkanSlopeAtrPerBar > 0.015 && trigSnap.kijunSlopeAtrPerBar >= -0.04 &&
                trigSnap.senkouSpanASlopeAtrPerBar > 0.0
        } else {
            trigSnap.tenkanSlopeAtrPerBar < -0.015 && trigSnap.kijunSlopeAtrPerBar <= 0.04 &&
                trigSnap.senkouSpanASlopeAtrPerBar < 0.0
        }
        val parsaLineSpacingOk = trigSnap.tenkanKijunGapAtr in 0.02..0.75 &&
            trigSnap.tenkanKijunGapExpansionAtr >= -0.02 && trigSnap.distanceToKijunAtr <= 1.50
        val parsaChikouOk = if (isDirectionalLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace
        val parsaSharpMoveOk = trigSnap.candleBodyRatio >= 0.35 &&
            (trigSnap.volumeRatio20 >= 1.10 || trigSnap.atrExpansionRatio52 >= 1.05) &&
            (if (isDirectionalLong) trigSnap.closeLocationValue >= 0.60 else trigSnap.closeLocationValue <= 0.40)
        val parsaCloudOk = if (isDirectionalLong) {
            trigSnap.close > trigSnap.kijun && (macroSnap == null || !macroSnap.isPriceBelowKumo)
        } else {
            trigSnap.close < trigSnap.kijun && (macroSnap == null || !macroSnap.isPriceAboveKumo)
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

**Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard** — خطوط ۴۰۴۳ تا ۴۰۵۲

```kotlin
        // ── Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard ────
        val sPointOk = wave?.sPointHeld != false
        val timeCycleExhausted = wave?.isNearTimeCycleChangeDay == true && trigSnap.isTenkanFlat
        val cond10Passed = sPointOk && !timeCycleExhausted
        val cond10Detail = if (wave != null) {
            "موج (${wave.pointA}→${wave.pointB}→${wave.pointC}) · کندل ${wave.barsSinceWaveStart} (چرخه هوسودا: ${wave.nearestTimeCycleNumber})"
        } else {
            "در حال تکمیل ساختار موج هوسودا"
        }
```
**Check 11: Price Action Candle Body, Close Location, Volume Momentum & Drive PDF Book Rules** — خطوط ۴۰۵۳ تا ۴۱۰۰

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

**Check 12: Room to HTF Plateau & Optimal R:R strictly in [1:1.50 .. 1:3.00]** — خطوط ۴۱۹۰ تا ۴۲۲۸

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

**Check 13: Directional Efficiency Ratio (Kaufman ER) & Anti-Chop Filter** — خطوط ۴۳۲۳ تا ۴۳۳۱

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
**Check 14: Anti-Fakeout Wick Guard & Liquidity Sweep Filter** — خطوط ۴۳۳۲ تا ۴۳۴۴

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
**Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion)** — خطوط ۴۳۴۵ تا ۴۳۴۸

```kotlin
        // ── Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion) ──
        val cond15Passed = trendPhase == TrendPhase.GENESIS || trendPhase == TrendPhase.CORE_EXPANSION
        val cond15Detail = "${trendPhase.badgeFa} · کندل $waveBars موج · فاصله کیجون: ${String.format(Locale.US, "%.2f", extAtr)} ATR"
```
**Check 16: Sniper Entry Precision & Anti-Chase Pocket** — خطوط ۴۳۴۹ تا ۴۳۵۳

```kotlin
        // ── Check 16: Sniper Entry Precision & Anti-Chase Pocket ──────────────
        val distFromTenkanAtr = abs(trigSnap.close - activeTenkanRef) / trigSnap.atr14.coerceAtLeast(1e-8)
        val maxSniperDist = effectiveConfig.maxSniperDistanceAtr.coerceIn(0.20, 0.55)
        val cond16Passed = distToSniperAtr <= maxSniperDist && distFromTenkanAtr <= (maxSniperDist + 0.50)
```
**Check 17 — اقتصاد معامله: نسبت هزینهٔ اجرا به ۱R** — خطوط ۴۳۵۴ تا ۴۳۷۸

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

## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR |
| چیکو درگیر کندل‌ها یا ابر ۲۶ دوره قبل است |
| فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s |
| SSB فلت شناسایی شد؛ عبور با بافر امن انجام شد |
| تفکیک استاندارد TK |
|  · دیوار تاج هیدن کراس=%.2f (%.2f ATR) %s |
| در حال تکمیل ساختار موج هوسودا |
| بدنه کندل: %.0f%% · CLV: %.0f%% · حجم به میانگین ۲۰: %.2fx%s |
| امتداد دامنهٔ ساختار ۵۲ کندلی |
| تاج کراس برابری (نصف اندازهٔ کراس) |
| هدف بهینه پس از کراس برابری |
| هدف حرکتی پس از چرخش Senkou A |
| موج بهینه هوسودا (%.2fR) |
|  + هندسهٔ الیوت–نئوویو (نسخهٔ پیمان، هم‌تراز جامع بیس) |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD BASE_ICHIMOKU
tools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD BASE_ICHIMOKU

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "BASE_ICHIMOKU" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/core/AiTraderNewsLayerTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BacktestEngineTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BacktestMonthlyTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BacktestNeuralGateWiringTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BacktestNeuralMemoryTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BacktestTradeArchiveTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BaseStrategyBookGateTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/BaseStrategyDeepDiveTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/CompressionExpansionEngineTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/EntrySafetyGateTest.kt`
- `android/app/src/test/java/com/aurum/edge/core/HeavyCsvGuardTest.kt`
- `android/app/src/test/java/com/aurum/edge/engine/RiskAndCalendarEngineTest.kt`
