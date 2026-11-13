# ۵ · مدل تکی ۴: پلاتوهای افقی SSB/Kijun و کاتانا (پلویل)
`PELOILLE_MTF_PLATEAU` · تکی · کارن پلویل

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `PELOILLE_MTF_PLATEAU` |
| نشان | تکی · کارن پلویل |
| مکتب‌ها | پلویل |
| تعداد مکتب | ۱ از ۴ |
| R:R پایه | ۱.۹۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | Karen Péloille (Trading with Ichimoku) |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
استخراج دیوارهای حمایت و مقاومت از فلت‌های Kijun و Senkou Span B در سه تایم‌فریم با تعادل کاتانا و آزادی حرکتی چیکو.

## دروازه‌هایی که این استراتژی روشن می‌کند
این پنج قاعده، دقیقاً همان چیزی است که کد از پرچم مکتب‌ها می‌سازد (`IchimokuStrategyEngine.kt`، بلوک `effectiveConfig`):

| دروازه | قاعدهٔ کد | وضعیت |
|---|---|---|
| ماشهٔ پولبک | `hasPatel یا hasHosoda یا hasPeloille یا بی‌تاتل` | روشن |
| ماشهٔ شکست | `hasTuttle یا hasHosoda یا hasPeloille یا بی‌پاتل` | روشن |
| الزام همسویی تایم بالا | `hasPeloille یا ترکیب ۲گانه و بیشتر` | روشن |
| الزام فضای آزاد چیکو | `hasTuttle یا hasPeloille یا ترکیب ۲گانه و بیشتر` | روشن |
| الزام شیب ابر آینده | `hasPatel یا hasTuttle یا ترکیب ۲گانه و بیشتر` | خاموش |

## شرط‌های ارزیابی

| # | شرط | مربوط به |
|---|---|---|
| ۱ | رژیم کوموی ۳ تایم‌فریم (تایم بالاتر/استراتژی/ماشه) | مشترک |
| ۲ | چیدمان ۵ خط سانیاکو (Sanyaku Order) | مشترک |
| ۴ | جهت و شیب ابر آینده (۲۶ کندل جلوتر) | مشترک |
| ۵ | ضخامت ابر آینده و ایمنی از پیچش (Twist) | مکتب پلویل |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۹ | فیلتر جاذبه فلت SSB، دیوار تاج هیدن کراس و تعادل کاتانا | مکتب پلویل |
| ۱۱ | قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol) | مشترک |
| ۱۲ | فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت | مشترک |
| ۱۳ | کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14) | مشترک |
| ۱۴ | فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس | مشترک |
| ۱۵ | شکار اصل روند (فاز Genesis/Core و عدم اشباع) | مشترک |
| ۱۶ | نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket) | مشترک |
| ۱۷ | اقتصاد اجرا — هزینهٔ رفت‌وبرگشت در برابر ۱R | مشترک |

شرط‌های پایه (۱، ۲، ۴، ۶) و لایهٔ کیفیت اجرا (۱۱ تا ۱۷) برای همهٔ ۵ مدل ایچیموکو یکی است؛ فقط شرط‌های مکتبی (۳، ۵، ۷، ۸، ۹، ۱۰) با پرچم مکتب‌ها روشن/خاموش می‌شوند. توضیح کامل در سند «۰۰ — موتور مشترک».

## کد
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
کد شرط‌های مشترک (۱، ۲، ۴، ۶ و ۱۱ تا ۱۷) این‌جا تکرار نشده؛ در سند [00-shared-pipeline.md](00-shared-pipeline.md) است. پایین فقط کد شرط‌های مکتبیِ همین روش آمده.

**Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل)** — خطوط ۲۹۲۶ تا ۲۹۳۶

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
**Check 9: Flat SSB Gravity & Katana Equilibrium Filter (تاتل + پلویل)** — خطوط ۲۹۷۵ تا ۳۰۹۴
<details><summary>کد کامل (۱۱۹ خط)</summary>

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
        val triggerType = detectEntryTrigger(
            direction = candidateDirection,
            bars = closedTriggerBars,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            config = effectiveConfig,
            activeModel = activeModel,
        )
```

</details>


## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| SSB فلت شناسایی شد؛ عبور با بافر امن انجام شد |
| تفکیک استاندارد TK |
|  · دیوار تاج هیدن کراس=%.2f (%.2f ATR) %s |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PELOILLE_MTF_PLATEAU
tools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PELOILLE_MTF_PLATEAU

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "PELOILLE_MTF_PLATEAU" android/app/src/main/java/com/aurum/edge | head -20
```

این روش تست اختصاصی ندارد؛ با تست‌های مشترک موتور سنجیده می‌شود: `BaseStrategyDeepDiveTest`، `BaseStrategyBookGateTest`، `BacktestMonthlyTest`.
