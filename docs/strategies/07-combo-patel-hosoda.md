# ۷ · ترکیب ۲گانه (پاتل + هوسودا): تعادل کیجون + موج N و زمان
`COMBO_PATEL_HOSODA` · ۲گانه · پاتل+هوسودا

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `COMBO_PATEL_HOSODA` |
| نشان | ۲گانه · پاتل+هوسودا |
| مکتب‌ها | پاتل + هوسودا |
| تعداد مکتب | ۲ از ۴ |
| R:R پایه | ۲.۲۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | Patel + Hosoda Hybrid |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
ورود نقطه‌زنی روی پولبک کیجون‌سن در نقطه S موج سوم هوسودا با تارگت‌گذاری دقیق روی امواج V، N و E و فیلتر چرخه زمانی.

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
| ۵ | ضخامت ابر آینده و ایمنی از پیچش (Twist) | مکتب هوسودا |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۸ | قانون کش لاستیکی کیجون و نبود C-Clamp | مکتب پاتل |
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
کد شرط‌های مشترک (۱، ۲، ۴، ۶ و ۱۱ تا ۱۷) این‌جا تکرار نشده؛ در سند [00-shared-pipeline.md](00-shared-pipeline.md) است. پایین فقط کد شرط‌های مکتبیِ همین روش آمده.

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

## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR |
| فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s |
| در حال تکمیل ساختار موج هوسودا |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD COMBO_PATEL_HOSODA
tools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD COMBO_PATEL_HOSODA

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "COMBO_PATEL_HOSODA" android/app/src/main/java/com/aurum/edge | head -20
```

این روش تست اختصاصی ندارد؛ با تست‌های مشترک موتور سنجیده می‌شود: `BaseStrategyDeepDiveTest`، `BaseStrategyBookGateTest`، `BacktestMonthlyTest`.
